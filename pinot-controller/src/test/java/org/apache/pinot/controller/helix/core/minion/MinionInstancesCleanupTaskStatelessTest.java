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
package org.apache.pinot.controller.helix.core.minion;

import java.util.Map;
import java.util.Properties;
import org.apache.pinot.common.metrics.ControllerGauge;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.helix.ControllerTest;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class MinionInstancesCleanupTaskStatelessTest extends ControllerTest {
  @BeforeClass
  public void setup()
      throws Exception {
    startZk();
    startController();
  }

  @Test
  public void testMinionInstancesCleanupTask()
      throws Exception {
    MinionInstancesCleanupTask minionInstancesCleanupTask = _controllerStarter.getMinionInstancesCleanupTask();
    minionInstancesCleanupTask.runTask(new Properties());
    Assert.assertEquals(
        _controllerStarter.getControllerMetrics().getValueOfGlobalGauge(ControllerGauge.DROPPED_MINION_INSTANCES), 0);
    addFakeMinionInstancesToAutoJoinHelixCluster(3);
    Assert.assertEquals(
        _controllerStarter.getControllerMetrics().getValueOfGlobalGauge(ControllerGauge.DROPPED_MINION_INSTANCES), 0);
    stopFakeInstance("Minion_localhost_0");
    Thread.sleep(1000);
    minionInstancesCleanupTask.runTask(new Properties());
    Assert.assertEquals(
        _controllerStarter.getControllerMetrics().getValueOfGlobalGauge(ControllerGauge.DROPPED_MINION_INSTANCES), 1);
    stopFakeInstance("Minion_localhost_1");
    Thread.sleep(1000);
    minionInstancesCleanupTask.runTask(new Properties());
    Assert.assertEquals(
        _controllerStarter.getControllerMetrics().getValueOfGlobalGauge(ControllerGauge.DROPPED_MINION_INSTANCES), 2);
    stopFakeInstance("Minion_localhost_2");
    Thread.sleep(1000);
    minionInstancesCleanupTask.runTask(new Properties());
    Assert.assertEquals(
        _controllerStarter.getControllerMetrics().getValueOfGlobalGauge(ControllerGauge.DROPPED_MINION_INSTANCES), 3);
  }

  @Override
  public Map<String, Object> getDefaultControllerConfiguration() {
    Map<String, Object> properties = super.getDefaultControllerConfiguration();
    // Override the cleanup before deletion period so that test can avoid stuck failure
    properties.put(ControllerConf.ControllerPeriodicTasksConf.
        MINION_INSTANCES_CLEANUP_TASK_MIN_OFFLINE_TIME_BEFORE_DELETION_PERIOD, "1s");
    return properties;
  }

  @AfterClass
  public void teardown() {
    stopController();
    stopZk();
  }
}
