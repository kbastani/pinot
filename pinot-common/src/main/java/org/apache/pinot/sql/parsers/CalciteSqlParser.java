/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.sql.parsers;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pinot.common.function.FunctionDefinitionRegistry;
import org.apache.pinot.common.request.DataSource;
import org.apache.pinot.common.request.Expression;
import org.apache.pinot.common.request.ExpressionType;
import org.apache.pinot.common.request.Function;
import org.apache.pinot.common.request.PinotQuery;
import org.apache.pinot.common.utils.request.RequestUtils;
import org.apache.pinot.segment.spi.AggregationFunctionType;
import org.apache.pinot.spi.utils.Pairs;
import org.apache.pinot.sql.parsers.rewriter.QueryRewriter;
import org.apache.pinot.sql.parsers.rewriter.QueryRewriterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CalciteSqlParser {
  private CalciteSqlParser() {
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(CalciteSqlParser.class);

  /** Lexical policy similar to MySQL with ANSI_QUOTES option enabled. (To be
   * precise: MySQL on Windows; MySQL on Linux uses case-sensitive matching,
   * like the Linux file system.) The case of identifiers is preserved whether
   * or not they quoted; after which, identifiers are matched
   * case-insensitively. Double quotes allow identifiers to contain
   * non-alphanumeric characters. */
  private static final Lex PINOT_LEX = Lex.MYSQL_ANSI;

  // BABEL is a very liberal conformance value that allows anything supported by any dialect
  private static final SqlParser.Config PARSER_CONFIG =
      SqlParser.configBuilder().setLex(PINOT_LEX).setConformance(SqlConformanceEnum.BABEL)
          .setParserFactory(SqlBabelParserImpl.FACTORY).build();

  public static final List<QueryRewriter> QUERY_REWRITERS = new ArrayList<>(QueryRewriterFactory.getQueryRewriters());

  // To Keep the backward compatibility with 'OPTION' Functionality in PQL, which is used to
  // provide more hints for query processing.
  //
  // PQL syntax is: `OPTION (<key> = <value>)`
  //
  // Multiple OPTIONs is also supported by:
  // either
  //   `OPTION (<k1> = <v1>, <k2> = <v2>, <k3> = <v3>)`
  // or
  //   `OPTION (<k1> = <v1>) OPTION (<k2> = <v2>) OPTION (<k3> = <v3>)`
  private static final Pattern OPTIONS_REGEX_PATTEN =
      Pattern.compile("option\\s*\\(([^\\)]+)\\)", Pattern.CASE_INSENSITIVE);

  /**
   * Checks for the presence of semicolon in the sql query and modifies the query accordingly
   *
   * @param sql sql query
   * @return sql query without semicolons
   *
   */
  private static String removeTerminatingSemicolon(String sql) {
    // trim all the leading and trailing whitespaces
    sql = sql.trim();
    int sqlLength = sql.length();

    // Terminate the semicolon only if the last character of the query is semicolon
    if (sql.charAt(sqlLength - 1) == ';') {
      return sql.substring(0, sqlLength - 1);
    }
    return sql;
  }

  public static PinotQuery compileToPinotQuery(String sql)
      throws SqlCompilationException {
    // Remove the comments from the query
    sql = removeComments(sql);

    // Remove the terminating semicolon from the query
    sql = removeTerminatingSemicolon(sql);

    // Extract OPTION statements from sql as Calcite Parser doesn't parse it.
    List<String> options = extractOptionsFromSql(sql);
    if (!options.isEmpty()) {
      sql = removeOptionsFromSql(sql);
    }
    // Compile Sql without OPTION statements.
    PinotQuery pinotQuery = compileCalciteSqlToPinotQuery(sql);

    // Set Option statements to PinotQuery.
    setOptions(pinotQuery, options);
    return pinotQuery;
  }

  static void validate(PinotQuery pinotQuery)
      throws SqlCompilationException {
    validateGroupByClause(pinotQuery);
    validateDistinctQuery(pinotQuery);
  }

  private static void validateGroupByClause(PinotQuery pinotQuery)
      throws SqlCompilationException {
    boolean hasGroupByClause = pinotQuery.getGroupByList() != null;
    Set<Expression> groupByExprs = hasGroupByClause ? new HashSet<>(pinotQuery.getGroupByList()) : null;
    int aggregateExprCount = 0;
    for (Expression selectExpression : pinotQuery.getSelectList()) {
      if (isAggregateExpression(selectExpression)) {
        aggregateExprCount++;
      } else if (hasGroupByClause && expressionOutsideGroupByList(selectExpression, groupByExprs)) {
          throw new SqlCompilationException(
            "'" + RequestUtils.prettyPrint(selectExpression) + "' should appear in GROUP BY clause.");
      }
    }

    // block mixture of aggregate and non-aggregate expression when group by is absent
    int nonAggregateExprCount = pinotQuery.getSelectListSize() - aggregateExprCount;
    if (!hasGroupByClause && aggregateExprCount > 0 && nonAggregateExprCount > 0) {
      throw new SqlCompilationException("Columns and Aggregate functions can't co-exist without GROUP BY clause");
    }
    // Sanity check on group by clause shouldn't contain aggregate expression.
    if (hasGroupByClause) {
      for (Expression groupByExpression : pinotQuery.getGroupByList()) {
        if (isAggregateExpression(groupByExpression)) {
          throw new SqlCompilationException("Aggregate expression '" + RequestUtils.prettyPrint(groupByExpression)
              + "' is not allowed in GROUP BY clause.");
        }
      }
    }
  }

  /*
   * Validate DISTINCT queries:
   * - No GROUP-BY clause
   * - LIMIT must be positive
   * - ORDER-BY columns (if exist) should be included in the DISTINCT columns
   */
  private static void validateDistinctQuery(PinotQuery pinotQuery)
      throws SqlCompilationException {
    List<Expression> selectList = pinotQuery.getSelectList();
    if (selectList.size() == 1) {
      Function function = selectList.get(0).getFunctionCall();
      if (function != null && function.getOperator().equalsIgnoreCase(AggregationFunctionType.DISTINCT.getName())) {
        if (CollectionUtils.isNotEmpty(pinotQuery.getGroupByList())) {
          // TODO: Explore if DISTINCT should be supported with GROUP BY
          throw new IllegalStateException("DISTINCT with GROUP BY is currently not supported");
        }
        if (pinotQuery.getLimit() <= 0) {
          // TODO: Consider changing it to SELECTION query for LIMIT 0
          throw new IllegalStateException("DISTINCT must have positive LIMIT");
        }
        List<Expression> orderByList = pinotQuery.getOrderByList();
        if (orderByList != null) {
          List<Expression> distinctExpressions = getAliasLeftExpressionsFromDistinctExpression(function);
          for (Expression orderByExpression : orderByList) {
            // NOTE: Order-by is always a Function with the ordering of the Expression
            if (!distinctExpressions.contains(orderByExpression.getFunctionCall().getOperands().get(0))) {
              throw new IllegalStateException("ORDER-BY columns should be included in the DISTINCT columns");
            }
          }
        }
      }
    }
  }

  private static List<Expression> getAliasLeftExpressionsFromDistinctExpression(Function function) {
    List<Expression> operands = function.getOperands();
    List<Expression> expressions = new ArrayList<>(operands.size());
    for (Expression operand : operands) {
      if (isAsFunction(operand)) {
        expressions.add(operand.getFunctionCall().getOperands().get(0));
      } else {
        expressions.add(operand);
      }
    }
    return expressions;
  }

  /**
   * Check recursively if an expression contains any reference not appearing in the GROUP BY clause.
   */
  private static boolean expressionOutsideGroupByList(Expression expr, Set<Expression> groupByExprs) {
    // return early for Literal, Aggregate and if we have an exact match
    if (expr.getType() == ExpressionType.LITERAL || isAggregateExpression(expr) || groupByExprs.contains(expr)) {
      return false;
    }

    final Function funcExpr = expr.getFunctionCall();
    // function expression
    if (funcExpr != null) {
      // for Alias function, check the actual value
      if (funcExpr.getOperator().equalsIgnoreCase(SqlKind.AS.toString())) {
        return expressionOutsideGroupByList(funcExpr.getOperands().get(0), groupByExprs);
      }
      // Expression is invalid if any of its children is invalid
      return funcExpr.getOperands().stream().anyMatch(e -> expressionOutsideGroupByList(e, groupByExprs));
    }
    return true;
  }

  public static boolean isAggregateExpression(Expression expression) {
    Function functionCall = expression.getFunctionCall();
    if (functionCall != null) {
      String operator = functionCall.getOperator();
      try {
        AggregationFunctionType.getAggregationFunctionType(operator);
        return true;
      } catch (IllegalArgumentException e) {
      }
      if (functionCall.getOperandsSize() > 0) {
        for (Expression operand : functionCall.getOperands()) {
          if (isAggregateExpression(operand)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public static boolean isAsFunction(Expression expression) {
    return expression.getFunctionCall() != null && expression.getFunctionCall().getOperator().equalsIgnoreCase("AS");
  }

  /**
   * Extract all the identifiers from given expressions.
   *
   * @param expressions
   * @param excludeAs if true, ignores the right side identifier for AS function.
   * @return all the identifier names.
   */
  public static Set<String> extractIdentifiers(List<Expression> expressions, boolean excludeAs) {
    Set<String> identifiers = new HashSet<>();
    for (Expression expression : expressions) {
      if (expression.getIdentifier() != null) {
        identifiers.add(expression.getIdentifier().getName());
      } else if (expression.getFunctionCall() != null) {
        if (excludeAs && expression.getFunctionCall().getOperator().equalsIgnoreCase("AS")) {
          identifiers.addAll(
              extractIdentifiers(Arrays.asList(expression.getFunctionCall().getOperands().get(0)), true));
          continue;
        } else {
          identifiers.addAll(extractIdentifiers(expression.getFunctionCall().getOperands(), excludeAs));
        }
      }
    }
    return identifiers;
  }

  /**
   * Compiles a String expression into {@link Expression}.
   *
   * @param expression String expression.
   * @return {@link Expression} equivalent of the string.
   *
   * @throws SqlCompilationException if String is not a valid expression.
   */
  public static Expression compileToExpression(String expression) {
    SqlParser sqlParser = SqlParser.create(expression, PARSER_CONFIG);
    SqlNode sqlNode;
    try {
      sqlNode = sqlParser.parseExpression();
    } catch (SqlParseException e) {
      throw new SqlCompilationException("Caught exception while parsing expression: " + expression, e);
    }
    return toExpression(sqlNode);
  }

  private static void setOptions(PinotQuery pinotQuery, List<String> optionsStatements) {
    if (optionsStatements.isEmpty()) {
      return;
    }
    Map<String, String> options = new HashMap<>();
    for (String optionsStatement : optionsStatements) {
      for (String option : optionsStatement.split(",")) {
        final String[] splits = option.split("=");
        if (splits.length != 2) {
          throw new SqlCompilationException("OPTION statement requires two parts separated by '='");
        }
        options.put(splits[0].trim(), splits[1].trim());
      }
    }
    pinotQuery.setQueryOptions(options);
  }

  private static PinotQuery compileCalciteSqlToPinotQuery(String sql) {
    SqlParser sqlParser = SqlParser.create(sql, PARSER_CONFIG);
    SqlNode sqlNode;
    try {
      sqlNode = sqlParser.parseQuery();
    } catch (SqlParseException e) {
      throw new SqlCompilationException("Caught exception while parsing query: " + sql, e);
    }

    PinotQuery pinotQuery = new PinotQuery();
    if (sqlNode instanceof SqlExplain) {
      // Extract sql node for the query
      sqlNode = ((SqlExplain) sqlNode).getExplicandum();
      pinotQuery.setExplain(true);
    }
    SqlSelect selectNode;
    if (sqlNode instanceof SqlOrderBy) {
      // Store order-by info into the select sql node
      SqlOrderBy orderByNode = (SqlOrderBy) sqlNode;
      selectNode = (SqlSelect) orderByNode.query;
      selectNode.setOrderBy(orderByNode.orderList);
      selectNode.setFetch(orderByNode.fetch);
      selectNode.setOffset(orderByNode.offset);
    } else {
      selectNode = (SqlSelect) sqlNode;
    }

    // SELECT
    if (selectNode.getModifierNode(SqlSelectKeyword.DISTINCT) != null) {
      // SELECT DISTINCT
      if (selectNode.getGroup() != null) {
        // TODO: explore support for GROUP BY with DISTINCT
        throw new SqlCompilationException("DISTINCT with GROUP BY is not supported");
      }
      pinotQuery.setSelectList(convertDistinctSelectList(selectNode.getSelectList()));
    } else {
      pinotQuery.setSelectList(convertSelectList(selectNode.getSelectList()));
    }
    // FROM
    SqlNode fromNode = selectNode.getFrom();
    if (fromNode != null) {
      DataSource dataSource = new DataSource();
      dataSource.setTableName(fromNode.toString());
      pinotQuery.setDataSource(dataSource);
    }
    // WHERE
    SqlNode whereNode = selectNode.getWhere();
    if (whereNode != null) {
      pinotQuery.setFilterExpression(toExpression(whereNode));
    }
    // GROUP-BY
    SqlNodeList groupByNodeList = selectNode.getGroup();
    if (groupByNodeList != null) {
      pinotQuery.setGroupByList(convertSelectList(groupByNodeList));
    }
    // HAVING
    SqlNode havingNode = selectNode.getHaving();
    if (havingNode != null) {
      pinotQuery.setHavingExpression(toExpression(havingNode));
    }
    // ORDER-BY
    SqlNodeList orderByNodeList = selectNode.getOrderList();
    if (orderByNodeList != null) {
      pinotQuery.setOrderByList(convertOrderByList(orderByNodeList));
    }
    // LIMIT
    SqlNode limitNode = selectNode.getFetch();
    if (limitNode != null) {
      pinotQuery.setLimit(((SqlNumericLiteral) limitNode).intValue(false));
    }
    // OFFSET
    SqlNode offsetNode = selectNode.getOffset();
    if (offsetNode != null) {
      pinotQuery.setOffset(((SqlNumericLiteral) offsetNode).intValue(false));
    }

    queryRewrite(pinotQuery);
    return pinotQuery;
  }

  private static void queryRewrite(PinotQuery pinotQuery) {
    for (QueryRewriter queryRewriter : QUERY_REWRITERS) {
      pinotQuery = queryRewriter.rewrite(pinotQuery);
    }
    // Validate
    validate(pinotQuery);
  }

  private static List<String> extractOptionsFromSql(String sql) {
    List<String> results = new ArrayList<>();
    Matcher matcher = OPTIONS_REGEX_PATTEN.matcher(sql);
    while (matcher.find()) {
      results.add(matcher.group(1));
    }
    return results;
  }

  private static String removeOptionsFromSql(String sql) {
    Matcher matcher = OPTIONS_REGEX_PATTEN.matcher(sql);
    return matcher.replaceAll("");
  }

  /**
   * Removes comments from the query.
   * NOTE: Comment indicator within single quotes (literal) and double quotes (identifier) are ignored.
   */
  @VisibleForTesting
  static String removeComments(String sql) {
    boolean openSingleQuote = false;
    boolean openDoubleQuote = false;
    boolean commented = false;
    boolean singleLineCommented = false;
    boolean multiLineCommented = false;
    int commentStartIndex = -1;
    List<Pairs.IntPair> commentedParts = new ArrayList<>();

    int length = sql.length();
    int index = 0;
    while (index < length) {
      switch (sql.charAt(index)) {
        case '\'':
          if (!commented && !openDoubleQuote) {
            openSingleQuote = !openSingleQuote;
          }
          break;
        case '"':
          if (!commented && !openSingleQuote) {
            openDoubleQuote = !openDoubleQuote;
          }
          break;
        case '-':
          // Single line comment start indicator: --
          if (!commented && !openSingleQuote && !openDoubleQuote && index < length - 1
              && sql.charAt(index + 1) == '-') {
            commented = true;
            singleLineCommented = true;
            commentStartIndex = index;
            index++;
          }
          break;
        case '\n':
          // Single line comment end indicator: \n
          if (singleLineCommented) {
            commentedParts.add(new Pairs.IntPair(commentStartIndex, index + 1));
            commented = false;
            singleLineCommented = false;
            commentStartIndex = -1;
          }
          break;
        case '/':
          // Multi-line comment start indicator: /*
          if (!commented && !openSingleQuote && !openDoubleQuote && index < length - 1
              && sql.charAt(index + 1) == '*') {
            commented = true;
            multiLineCommented = true;
            commentStartIndex = index;
            index++;
          }
          break;
        case '*':
          // Multi-line comment end indicator: */
          if (multiLineCommented && index < length - 1 && sql.charAt(index + 1) == '/') {
            commentedParts.add(new Pairs.IntPair(commentStartIndex, index + 2));
            commented = false;
            multiLineCommented = false;
            commentStartIndex = -1;
            index++;
          }
          break;
        default:
          break;
      }
      index++;
    }

    if (commentedParts.isEmpty()) {
      if (singleLineCommented) {
        return sql.substring(0, commentStartIndex);
      } else {
        return sql;
      }
    } else {
      StringBuilder stringBuilder = new StringBuilder();
      int startIndex = 0;
      for (Pairs.IntPair commentedPart : commentedParts) {
        stringBuilder.append(sql, startIndex, commentedPart.getLeft()).append(' ');
        startIndex = commentedPart.getRight();
      }
      if (startIndex < length) {
        if (singleLineCommented) {
          stringBuilder.append(sql, startIndex, commentStartIndex);
        } else {
          stringBuilder.append(sql, startIndex, length);
        }
      }
      return stringBuilder.toString();
    }
  }

  private static List<Expression> convertDistinctSelectList(SqlNodeList selectList) {
    List<Expression> selectExpr = new ArrayList<>();
    selectExpr.add(convertDistinctAndSelectListToFunctionExpression(selectList));
    return selectExpr;
  }

  private static List<Expression> convertSelectList(SqlNodeList selectList) {
    List<Expression> selectExpr = new ArrayList<>();

    final Iterator<SqlNode> iterator = selectList.iterator();
    while (iterator.hasNext()) {
      final SqlNode next = iterator.next();
      selectExpr.add(toExpression(next));
    }

    return selectExpr;
  }

  private static List<Expression> convertOrderByList(SqlNodeList orderList) {
    List<Expression> orderByExpr = new ArrayList<>();
    final Iterator<SqlNode> iterator = orderList.iterator();
    while (iterator.hasNext()) {
      final SqlNode next = iterator.next();
      orderByExpr.add(convertOrderBy(next));
    }
    return orderByExpr;
  }

  private static Expression convertOrderBy(SqlNode node) {
    final SqlKind kind = node.getKind();
    Expression expression;
    switch (kind) {
      case DESCENDING:
        SqlBasicCall basicCall = (SqlBasicCall) node;
        expression = RequestUtils.getFunctionExpression("DESC");
        expression.getFunctionCall().addToOperands(toExpression(basicCall.getOperands()[0]));
        break;
      case IDENTIFIER:
      default:
        expression = RequestUtils.getFunctionExpression("ASC");
        expression.getFunctionCall().addToOperands(toExpression(node));
        break;
    }
    return expression;
  }

  /**
   * DISTINCT is implemented as an aggregation function so need to take the select list items
   * and convert them into a single function expression for handing over to execution engine
   * either as a PinotQuery or BrokerRequest via conversion
   * @param selectList select list items
   * @return DISTINCT function expression
   */
  private static Expression convertDistinctAndSelectListToFunctionExpression(SqlNodeList selectList) {
    String functionName = AggregationFunctionType.DISTINCT.getName();
    Expression functionExpression = RequestUtils.getFunctionExpression(functionName);
    for (SqlNode node : selectList) {
      Expression columnExpression = toExpression(node);
      if (columnExpression.getType() == ExpressionType.IDENTIFIER && columnExpression.getIdentifier().getName()
          .equals("*")) {
        throw new SqlCompilationException(
            "Syntax error: Pinot currently does not support DISTINCT with *. Please specify each column name after "
                + "DISTINCT keyword");
      } else if (columnExpression.getType() == ExpressionType.FUNCTION) {
        Function functionCall = columnExpression.getFunctionCall();
        String function = functionCall.getOperator();
        if (FunctionDefinitionRegistry.isAggFunc(function)) {
          throw new SqlCompilationException(
              "Syntax error: Use of DISTINCT with aggregation functions is not supported");
        }
      }
      functionExpression.getFunctionCall().addToOperands(columnExpression);
    }
    return functionExpression;
  }

  private static Expression toExpression(SqlNode node) {
    LOGGER.debug("Current processing SqlNode: {}, node.getKind(): {}", node, node.getKind());
    switch (node.getKind()) {
      case IDENTIFIER:
        if (((SqlIdentifier) node).isStar()) {
          return RequestUtils.getIdentifierExpression("*");
        }
        if (((SqlIdentifier) node).isSimple()) {
          return RequestUtils.getIdentifierExpression(((SqlIdentifier) node).getSimple());
        }
        return RequestUtils.getIdentifierExpression(node.toString());
      case LITERAL:
        return RequestUtils.getLiteralExpression((SqlLiteral) node);
      case AS:
        SqlBasicCall asFuncSqlNode = (SqlBasicCall) node;
        Expression leftExpr = toExpression(asFuncSqlNode.getOperands()[0]);
        SqlNode aliasSqlNode = asFuncSqlNode.getOperands()[1];
        String aliasName;
        switch (aliasSqlNode.getKind()) {
          case IDENTIFIER:
            aliasName = ((SqlIdentifier) aliasSqlNode).getSimple();
            break;
          case LITERAL:
            aliasName = ((SqlLiteral) aliasSqlNode).toValue();
            break;
          default:
            throw new SqlCompilationException("Unsupported Alias sql node - " + aliasSqlNode);
        }
        Expression rightExpr = RequestUtils.getIdentifierExpression(aliasName);
        // Just return left identifier if both sides are the same identifier.
        if (leftExpr.isSetIdentifier() && rightExpr.isSetIdentifier()) {
          if (leftExpr.getIdentifier().getName().equals(rightExpr.getIdentifier().getName())) {
            return leftExpr;
          }
        }
        final Expression asFuncExpr = RequestUtils.getFunctionExpression(SqlKind.AS.toString());
        asFuncExpr.getFunctionCall().addToOperands(leftExpr);
        asFuncExpr.getFunctionCall().addToOperands(rightExpr);
        return asFuncExpr;
      case CASE:
        // CASE WHEN Statement is model as a function with variable length parameters.
        // Assume N is number of WHEN Statements, total number of parameters is (2 * N + 1).
        // - N: Convert each WHEN Statement into a function Expression;
        // - N: Convert each THEN Statement into an Expression;
        // - 1: Convert ELSE Statement into an Expression.
        SqlCase caseSqlNode = (SqlCase) node;
        SqlNodeList whenOperands = caseSqlNode.getWhenOperands();
        SqlNodeList thenOperands = caseSqlNode.getThenOperands();
        SqlNode elseOperand = caseSqlNode.getElseOperand();
        Expression caseFuncExpr = RequestUtils.getFunctionExpression(SqlKind.CASE.name());
        for (SqlNode whenSqlNode : whenOperands.getList()) {
          Expression whenExpression = toExpression(whenSqlNode);
          if (isAggregateExpression(whenExpression)) {
            throw new SqlCompilationException(
                "Aggregation functions inside WHEN Clause is not supported - " + whenSqlNode);
          }
          caseFuncExpr.getFunctionCall().addToOperands(whenExpression);
        }
        for (SqlNode thenSqlNode : thenOperands.getList()) {
          Expression thenExpression = toExpression(thenSqlNode);
          if (isAggregateExpression(thenExpression)) {
            throw new SqlCompilationException(
                "Aggregation functions inside THEN Clause is not supported - " + thenSqlNode);
          }
          caseFuncExpr.getFunctionCall().addToOperands(thenExpression);
        }
        Expression elseExpression = toExpression(elseOperand);
        if (isAggregateExpression(elseExpression)) {
          throw new SqlCompilationException(
              "Aggregation functions inside ELSE Clause is not supported - " + elseExpression);
        }
        caseFuncExpr.getFunctionCall().addToOperands(elseExpression);
        return caseFuncExpr;
      default:
        if (node instanceof SqlDataTypeSpec) {
          // This is to handle expression like: CAST(col AS INT)
          return RequestUtils.getLiteralExpression(((SqlDataTypeSpec) node).getTypeName().getSimple());
        } else {
          return compileFunctionExpression((SqlBasicCall) node);
        }
    }
  }

  private static Expression compileFunctionExpression(SqlBasicCall functionNode) {
    SqlKind functionKind = functionNode.getKind();
    String functionName;
    switch (functionKind) {
      case AND:
        return compileAndExpression(functionNode);
      case OR:
        return compileOrExpression(functionNode);
      case COUNT:
        SqlLiteral functionQuantifier = functionNode.getFunctionQuantifier();
        if (functionQuantifier != null && functionQuantifier.toValue().equalsIgnoreCase("DISTINCT")) {
          functionName = AggregationFunctionType.DISTINCTCOUNT.name();
        } else {
          functionName = AggregationFunctionType.COUNT.name();
        }
        break;
      case OTHER:
      case OTHER_FUNCTION:
      case DOT:
        functionName = functionNode.getOperator().getName().toUpperCase();
        if (functionName.equals("ITEM") || functionName.equals("DOT")) {
          // Calcite parses path expression such as "data[0][1].a.b[0]" into a chain of ITEM and/or DOT
          // functions. Collapse this chain into an identifier.
          StringBuffer path = new StringBuffer();
          compilePathExpression(functionName, functionNode, path);
          return RequestUtils.getIdentifierExpression(path.toString());
        }
        break;
      default:
        functionName = functionKind.name();
        break;
    }
    // When there is no argument, set an empty list as the operands
    SqlNode[] childNodes = functionNode.getOperands();
    List<Expression> operands = new ArrayList<>(childNodes.length);
    for (SqlNode childNode : childNodes) {
      if (childNode instanceof SqlNodeList) {
        for (SqlNode node : (SqlNodeList) childNode) {
          operands.add(toExpression(node));
        }
      } else {
        operands.add(toExpression(childNode));
      }
    }
    validateFunction(functionName, operands);
    Expression functionExpression = RequestUtils.getFunctionExpression(functionName);
    functionExpression.getFunctionCall().setOperands(operands);
    return functionExpression;
  }

  /**
   * Convert Calcite operator tree made up of ITEM and DOT functions to an identifier. For example, the operator tree
   * shown below will be converted to IDENTIFIER "jsoncolumn.data[0][1].a.b[0]".
   *
   * ├── ITEM(jsoncolumn.data[0][1].a.b[0])
   *      ├── LITERAL (0)
   *      └── DOT (jsoncolumn.daa[0][1].a.b)
   *            ├── IDENTIFIER (b)
   *            └── DOT (jsoncolumn.data[0][1].a)
   *                  ├── IDENTIFIER (a)
   *                  └── ITEM (jsoncolumn.data[0][1])
   *                        ├── LITERAL (1)
   *                        └── ITEM (jsoncolumn.data[0])
   *                              ├── LITERAL (1)
   *                              └── IDENTIFIER (jsoncolumn.data)
   *
   * @param functionName Name of the function ("DOT" or "ITEM")
   * @param functionNode Root node of the DOT and/or ITEM operator function chain.
   * @param path String representation of path represented by DOT and/or ITEM function chain.
   */
  private static void compilePathExpression(String functionName, SqlBasicCall functionNode, StringBuffer path) {
    SqlNode[] operands = functionNode.getOperands();

    // Compile first operand of the function (either an identifier or another DOT and/or ITEM function).
    SqlKind kind0 = operands[0].getKind();
    if (kind0 == SqlKind.IDENTIFIER) {
      path.append(operands[0].toString());
    } else if (kind0 == SqlKind.DOT || kind0 == SqlKind.OTHER_FUNCTION) {
      SqlBasicCall function0 = (SqlBasicCall) operands[0];
      String name0 = function0.getOperator().getName();
      if (name0.equals("ITEM") || name0.equals("DOT")) {
        compilePathExpression(name0, function0, path);
      } else {
        throw new SqlCompilationException("SELECT list item has bad path expression.");
      }
    } else {
      throw new SqlCompilationException("SELECT list item has bad path expression.");
    }

    // Compile second operand of the function (either an identifier or literal).
    SqlKind kind1 = operands[1].getKind();
    if (kind1 == SqlKind.IDENTIFIER) {
      path.append(".").append(((SqlIdentifier) operands[1]).getSimple());
    } else if (kind1 == SqlKind.LITERAL) {
      path.append("[").append(((SqlLiteral) operands[1]).toValue()).append("]");
    } else {
      throw new SqlCompilationException("SELECT list item has bad path expression.");
    }
  }

  public static String canonicalize(String functionName) {
    return StringUtils.remove(functionName, '_').toLowerCase();
  }

  public static boolean isSameFunction(String function1, String function2) {
    return canonicalize(function1).equals(canonicalize(function2));
  }

  private static void validateFunction(String functionName, List<Expression> operands) {
    switch (canonicalize(functionName)) {
      case "jsonextractscalar":
        validateJsonExtractScalarFunction(operands);
        break;
      case "jsonextractkey":
        validateJsonExtractKeyFunction(operands);
        break;
      default:
        break;
    }
  }

  private static void validateJsonExtractScalarFunction(List<Expression> operands) {
    int numOperands = operands.size();

    // Check that there are exactly 3 or 4 arguments
    if (numOperands != 3 && numOperands != 4) {
      throw new SqlCompilationException(
          "Expect 3 or 4 arguments for transform function: jsonExtractScalar(jsonFieldName, 'jsonPath', "
              + "'resultsType', ['defaultValue'])");
    }
    if (!operands.get(1).isSetLiteral() || !operands.get(2).isSetLiteral() || (numOperands == 4 && !operands.get(3)
        .isSetLiteral())) {
      throw new SqlCompilationException(
          "Expect the 2nd/3rd/4th argument of transform function: jsonExtractScalar(jsonFieldName, 'jsonPath',"
              + " 'resultsType', ['defaultValue']) to be a single-quoted literal value.");
    }
  }

  private static void validateJsonExtractKeyFunction(List<Expression> operands) {
    // Check that there are exactly 2 arguments
    if (operands.size() != 2) {
      throw new SqlCompilationException(
          "Expect 2 arguments are required for transform function: jsonExtractKey(jsonFieldName, 'jsonPath')");
    }
    if (!operands.get(1).isSetLiteral()) {
      throw new SqlCompilationException(
          "Expect the 2nd argument for transform function: jsonExtractKey(jsonFieldName, 'jsonPath') to be a "
              + "single-quoted literal value.");
    }
  }

  /**
   * Helper method to flatten the operands for the AND expression.
   */
  private static Expression compileAndExpression(SqlBasicCall andNode) {
    List<Expression> operands = new ArrayList<>();
    for (SqlNode childNode : andNode.getOperands()) {
      if (childNode.getKind() == SqlKind.AND) {
        Expression childAndExpression = compileAndExpression((SqlBasicCall) childNode);
        operands.addAll(childAndExpression.getFunctionCall().getOperands());
      } else {
        operands.add(toExpression(childNode));
      }
    }
    Expression andExpression = RequestUtils.getFunctionExpression(SqlKind.AND.name());
    andExpression.getFunctionCall().setOperands(operands);
    return andExpression;
  }

  /**
   * Helper method to flatten the operands for the OR expression.
   */
  private static Expression compileOrExpression(SqlBasicCall orNode) {
    List<Expression> operands = new ArrayList<>();
    for (SqlNode childNode : orNode.getOperands()) {
      if (childNode.getKind() == SqlKind.OR) {
        Expression childAndExpression = compileOrExpression((SqlBasicCall) childNode);
        operands.addAll(childAndExpression.getFunctionCall().getOperands());
      } else {
        operands.add(toExpression(childNode));
      }
    }
    Expression andExpression = RequestUtils.getFunctionExpression(SqlKind.OR.name());
    andExpression.getFunctionCall().setOperands(operands);
    return andExpression;
  }

  public static boolean isLiteralOnlyExpression(Expression e) {
    if (e.getType() == ExpressionType.LITERAL) {
      return true;
    }
    if (e.getType() == ExpressionType.FUNCTION) {
      Function functionCall = e.getFunctionCall();
      if (functionCall.getOperator().equalsIgnoreCase(SqlKind.AS.toString())) {
        return isLiteralOnlyExpression(functionCall.getOperands().get(0));
      }
      return false;
    }
    return false;
  }
}
