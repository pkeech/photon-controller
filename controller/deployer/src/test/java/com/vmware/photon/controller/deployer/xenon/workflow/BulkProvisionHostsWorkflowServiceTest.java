/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.deployer.xenon.workflow;

import com.vmware.photon.controller.agent.gen.AgentStatusCode;
import com.vmware.photon.controller.agent.gen.ProvisionResultCode;
import com.vmware.photon.controller.api.model.UsageTag;
import com.vmware.photon.controller.common.clients.AgentControlClientFactory;
import com.vmware.photon.controller.common.clients.HostClientFactory;
import com.vmware.photon.controller.common.config.ConfigBuilder;
import com.vmware.photon.controller.common.tests.nsx.NsxClientMock;
import com.vmware.photon.controller.common.xenon.CloudStoreHelper;
import com.vmware.photon.controller.common.xenon.ControlFlags;
import com.vmware.photon.controller.common.xenon.TaskUtils;
import com.vmware.photon.controller.common.xenon.exceptions.BadRequestException;
import com.vmware.photon.controller.common.xenon.host.PhotonControllerXenonHost;
import com.vmware.photon.controller.common.xenon.validation.Immutable;
import com.vmware.photon.controller.common.xenon.validation.NotNull;
import com.vmware.photon.controller.deployer.helpers.ReflectionUtils;
import com.vmware.photon.controller.deployer.helpers.TestHelper;
import com.vmware.photon.controller.deployer.helpers.xenon.DeployerTestConfig;
import com.vmware.photon.controller.deployer.helpers.xenon.MockHelper;
import com.vmware.photon.controller.deployer.helpers.xenon.TestEnvironment;
import com.vmware.photon.controller.deployer.helpers.xenon.TestHost;
import com.vmware.photon.controller.deployer.xenon.mock.AgentControlClientMock;
import com.vmware.photon.controller.deployer.xenon.mock.HostClientMock;
import com.vmware.photon.controller.deployer.xenon.task.ProvisionHostTaskService;
import com.vmware.photon.controller.deployer.xenon.task.UploadVibTaskService;
import com.vmware.photon.controller.deployer.xenon.util.MiscUtils;
import com.vmware.photon.controller.host.gen.GetConfigResultCode;
import com.vmware.photon.controller.host.gen.HostConfig;
import com.vmware.photon.controller.nsxclient.NsxClientFactory;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.UriUtils;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.commons.io.FileUtils;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import javax.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.Executors;

/**
 * This class implements tests for the {@link BulkProvisionHostsWorkflowService} class.
 */
public class BulkProvisionHostsWorkflowServiceTest {

  @Test(enabled = false)
  public void dummy() {
  }

  /**
   * This class implements tests for the constructor.
   */
  public static class InitializationTest {

    private BulkProvisionHostsWorkflowService bulkProvisionHostsWorkflowService;

    @BeforeMethod
    public void setUpTest() {
      bulkProvisionHostsWorkflowService = new BulkProvisionHostsWorkflowService();
    }

    @Test
    public void testCapabilities() {

      EnumSet<Service.ServiceOption> expected = EnumSet.of(
          Service.ServiceOption.CONCURRENT_GET_HANDLING,
          Service.ServiceOption.OWNER_SELECTION,
          Service.ServiceOption.PERSISTENCE,
          Service.ServiceOption.REPLICATION);

      assertThat(bulkProvisionHostsWorkflowService.getOptions(), is(expected));
    }
  }

  /**
   * This class implements tests for the handleStart method.
   */
  public static class HandleStartTest {

    private BulkProvisionHostsWorkflowService bulkProvisionHostsWorkflowService;
    private Boolean serviceCreated = false;
    private TestHost testHost;

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      bulkProvisionHostsWorkflowService = new BulkProvisionHostsWorkflowService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (serviceCreated) {
        testHost.deleteServiceSynchronously();
        serviceCreated = false;
      }
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(testHost);
    }

    @Test(dataProvider = "ValidStartStages")
    public void testValidStartState(
        TaskState.TaskStage startStage) throws Throwable {
      startService(buildValidStartState(startStage));

      BulkProvisionHostsWorkflowService.State serviceState =
          testHost.getServiceState(BulkProvisionHostsWorkflowService.State.class);

      assertThat(serviceState.taskState, notNullValue());
      assertThat(serviceState.deploymentServiceLink, is("DEPLOYMENT_SERVICE_LINK"));
      assertThat(serviceState.querySpecification, notNullValue());
    }

    @DataProvider(name = "ValidStartStages")
    public Object[][] getValidStartStages() {
      return TestHelper.getValidStartStages(null);
    }

    @Test(dataProvider = "TerminalStartStages")
    public void testTerminalStartState(TaskState.TaskStage startStage)
        throws Throwable {
      BulkProvisionHostsWorkflowService.State startState = buildValidStartState(startStage);
      startState.controlFlags = null;
      startService(startState);

      BulkProvisionHostsWorkflowService.State serviceState =
          testHost.getServiceState(BulkProvisionHostsWorkflowService.State.class);

      assertThat(serviceState.taskState.stage, is(startStage));
    }

    @DataProvider(name = "TerminalStartStages")
    public Object[][] getTerminalStartStages() {
      return new Object[][]{
          {TaskState.TaskStage.FINISHED},
          {TaskState.TaskStage.FAILED},
          {TaskState.TaskStage.CANCELLED},
      };
    }

    @Test(dataProvider = "RequiredFieldNames", expectedExceptions = BadRequestException.class)
    public void testInvalidStartStateMissingRequiredField(String fieldName) throws Throwable {
      BulkProvisionHostsWorkflowService.State startState = buildValidStartState(null);
      startState.getClass().getDeclaredField(fieldName).set(startState, null);
      startService(startState);
    }

    @DataProvider(name = "RequiredFieldNames")
    public Object[][] getRequiredFieldNames() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              BulkProvisionHostsWorkflowService.State.class, NotNull.class));
    }

    private void startService(BulkProvisionHostsWorkflowService.State startState) throws Throwable {
      Operation startOperation = testHost.startServiceSynchronously(bulkProvisionHostsWorkflowService, startState);
      assertThat(startOperation.getStatusCode(), is(200));
      serviceCreated = true;
    }
  }

  /**
   * This class implements tests for the handlePatch test.
   */
  public static class HandlePatchTest {

    private BulkProvisionHostsWorkflowService bulkProvisionHostsWorkflowService;
    private TestHost testHost;

    @BeforeClass
    public void setUpClass() throws Throwable {
      testHost = TestHost.create();
    }

    @BeforeMethod
    public void setUpTest() {
      bulkProvisionHostsWorkflowService = new BulkProvisionHostsWorkflowService();
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      testHost.deleteServiceSynchronously();
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      TestHost.destroy(testHost);
    }

    @Test(dataProvider = "ValidStageTransitions")
    public void testValidStageTransition(
        TaskState.TaskStage startStage,
        TaskState.TaskStage patchStage) throws Throwable {

      BulkProvisionHostsWorkflowService.State startState = buildValidStartState(startStage);
      Operation startOperation = testHost.startServiceSynchronously(bulkProvisionHostsWorkflowService, startState);
      assertThat(startOperation.getStatusCode(), is(200));

      Operation patchOperation = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(BulkProvisionHostsWorkflowService.buildPatch(patchStage, null));

      Operation result = testHost.sendRequestAndWait(patchOperation);
      assertThat(result.getStatusCode(), is(200));

      BulkProvisionHostsWorkflowService.State serviceState =
          testHost.getServiceState(BulkProvisionHostsWorkflowService.State.class);

      assertThat(serviceState.taskState.stage, is(patchStage));
    }

    @DataProvider(name = "ValidStageTransitions")
    public Object[][] getValidStageTransitions() {
      return TestHelper.getValidStageTransitions(null);
    }

    @Test(dataProvider = "InvalidStageTransitions", expectedExceptions = BadRequestException.class)
    public void testInvalidStageTransition(
        TaskState.TaskStage startStage,
        TaskState.TaskStage patchStage) throws Throwable {

      BulkProvisionHostsWorkflowService.State startState = buildValidStartState(startStage);
      Operation startOperation = testHost.startServiceSynchronously(bulkProvisionHostsWorkflowService, startState);
      assertThat(startOperation.getStatusCode(), is(200));

      Operation patchOperation = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(BulkProvisionHostsWorkflowService.buildPatch(patchStage, null));

      testHost.sendRequestAndWait(patchOperation);
    }

    @DataProvider(name = "InvalidStageTransitions")
    public Object[][] getInvalidStageTransitions() {
      return TestHelper.getInvalidStageTransitions(null);
    }

    @Test(dataProvider = "ImmutableFieldNames", expectedExceptions = BadRequestException.class)
    public void testInvalidPatchImmutableFieldChanged(String fieldName) throws Throwable {
      BulkProvisionHostsWorkflowService.State startState = buildValidStartState(null);
      Operation startOperation = testHost.startServiceSynchronously(bulkProvisionHostsWorkflowService, startState);
      assertThat(startOperation.getStatusCode(), is(200));

      BulkProvisionHostsWorkflowService.State patchState = BulkProvisionHostsWorkflowService.buildPatch(
          TaskState.TaskStage.STARTED, null);

      Field declaredField = patchState.getClass().getDeclaredField(fieldName);
      declaredField.set(patchState, ReflectionUtils.getDefaultAttributeValue(declaredField));

      Operation patchOperation = Operation
          .createPatch(UriUtils.buildUri(testHost, TestHost.SERVICE_URI))
          .setBody(patchState);

      testHost.sendRequestAndWait(patchOperation);
    }

    @DataProvider(name = "ImmutableFieldNames")
    public Object[][] getImmutableFieldNames() {
      return TestHelper.toDataProvidersList(
          ReflectionUtils.getAttributeNamesWithAnnotation(
              BulkProvisionHostsWorkflowService.State.class, Immutable.class));
    }
  }

  /**
   * This class implements end-to-end tests for the service.
   */
  public static class EndToEndTest {

    private static final String configFilePath = "/config.yml";

    private final File destinationDirectory = new File("/tmp/deployAgent/output");
    private final File scriptDirectory = new File("/tmp/deployAgent/scripts");
    private final File scriptLogDirectory = new File("/tmp/deployAgent/logs");
    private final File storageDirectory = new File("/tmp/deployAgent");
    private final File vibDirectory = new File("/tmp/deployAgent/vibs");

    private DeployerTestConfig deployerTestConfig;
    private AgentControlClientFactory agentControlClientFactory;
    private HostClientFactory hostClientFactory;
    private NsxClientFactory nsxClientFactory;
    private ListeningExecutorService listeningExecutorService;
    private BulkProvisionHostsWorkflowService.State startState;
    private TestEnvironment testEnvironment;
    private File vibSourceFile;

    @BeforeClass
    public void setUpClass() throws Throwable {
      FileUtils.deleteDirectory(storageDirectory);
      vibDirectory.mkdirs();
      vibSourceFile = TestHelper.createSourceFile(null, vibDirectory);

      deployerTestConfig = ConfigBuilder.build(DeployerTestConfig.class,
          this.getClass().getResource(configFilePath).getPath());
      TestHelper.setContainersConfig(deployerTestConfig);
      listeningExecutorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));

      startState = buildValidStartState(null);
      startState.querySpecification = MiscUtils.generateHostQuerySpecification(null, UsageTag.MGMT.name());
      startState.controlFlags = null;
    }

    @BeforeMethod
    public void setUpTest() throws Throwable {
      destinationDirectory.mkdirs();
      scriptDirectory.mkdirs();
      scriptLogDirectory.mkdirs();
      agentControlClientFactory = mock(AgentControlClientFactory.class);
      hostClientFactory = mock(HostClientFactory.class);
      nsxClientFactory = mock(NsxClientFactory.class);
    }

    private void createTestEnvironment(int hostCount) throws Throwable {

      testEnvironment = new TestEnvironment.Builder()
          .containersConfig(deployerTestConfig.getContainersConfig())
          .deployerContext(deployerTestConfig.getDeployerContext())
          .agentControlClientFactory(agentControlClientFactory)
          .hostClientFactory(hostClientFactory)
          .nsxClientFactory(nsxClientFactory)
          .listeningExecutorService(listeningExecutorService)
          .hostCount(hostCount)
          .build();

      for (PhotonControllerXenonHost host : testEnvironment.getHosts()) {
        host.setCloudStoreHelper(new CloudStoreHelper(testEnvironment.getServerSet()));
      }

      AgentControlClientMock agentControlClientMock = new AgentControlClientMock.Builder()
          .provisionResultCode(ProvisionResultCode.OK)
          .agentStatusCode(AgentStatusCode.OK)
          .build();

      doReturn(agentControlClientMock).when(agentControlClientFactory).create();

      HostClientMock hostClientMock = new HostClientMock.Builder()
          .getConfigResultCode(GetConfigResultCode.OK)
          .hostConfig(new HostConfig())
          .build();

      doReturn(hostClientMock).when(hostClientFactory).create();

      NsxClientMock nsxClientMock = new NsxClientMock.Builder()
          .registerFabricNode(true, "fabricNodeId")
          .createTransportNode(true, "transportNodeId")
          .getTransportZone(true, "transportZoneId", "hostSwitchName")
          .build();
      doReturn(nsxClientMock).when(nsxClientFactory).create(anyString(), anyString(), anyString());

    }

    private void createHostEntities(int mgmtCount, int cloudCount, int mixedCount, String deploymentServiceLink)
        throws Throwable {

      for (int i = 0; i < mgmtCount; i++) {
        TestHelper.createHostService(testEnvironment, Collections.singleton(UsageTag.MGMT.name()));
      }

      for (int i = 0; i < cloudCount; i++) {
        TestHelper.createHostService(testEnvironment, Collections.singleton(UsageTag.CLOUD.name()));
      }

      for (int i = 0; i < mixedCount; i++) {
        TestHelper.createHostService(testEnvironment, ImmutableSet.of(UsageTag.CLOUD.name(), UsageTag.MGMT.name()));
      }

      CreateManagementPlaneLayoutWorkflowService.State workflowStartState =
          new CreateManagementPlaneLayoutWorkflowService.State();
      workflowStartState.hostQuerySpecification = MiscUtils.generateHostQuerySpecification(null, UsageTag.MGMT.name());
      workflowStartState.deploymentServiceLink = deploymentServiceLink;

      CreateManagementPlaneLayoutWorkflowService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              CreateManagementPlaneLayoutWorkflowFactoryService.SELF_LINK,
              workflowStartState,
              CreateManagementPlaneLayoutWorkflowService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      TestHelper.assertTaskStateFinished(finalState.taskState);
    }

    @AfterMethod
    public void tearDownTest() throws Throwable {
      if (null != testEnvironment) {
        testEnvironment.stop();
        testEnvironment = null;
      }

      FileUtils.deleteDirectory(destinationDirectory);
      FileUtils.deleteDirectory(scriptDirectory);
      FileUtils.deleteDirectory(scriptLogDirectory);
    }

    @AfterClass
    public void tearDownClass() throws Throwable {
      listeningExecutorService.shutdown();
      FileUtils.forceDelete(vibSourceFile);
      FileUtils.deleteDirectory(storageDirectory);
    }

    @DataProvider(name = "HostCounts")
    public Object[][] getHostCounts() {
      return new Object[][]{
          // hostCount, mgmtHostCount, cloudHostCount, mixedHostCount
          {new Integer(1), new Integer(3), new Integer(7), new Integer(2)},
          {new Integer(1), new Integer(3), new Integer(0), new Integer(2)},
          {new Integer(1), new Integer(3), new Integer(7), new Integer(0)},
          {new Integer(1), new Integer(1), new Integer(0), new Integer(0)},
          {new Integer(1), new Integer(0), new Integer(7), new Integer(2)},
          {new Integer(1), new Integer(0), new Integer(0), new Integer(2)},
      };
    }

    @Test(dataProvider = "HostCounts")
    public void testEndToEndSuccessWithoutVirtualNetwork(
        Integer hostCount,
        Integer mgmtHostCount,
        Integer cloudHostCout,
        Integer mixedHostCount) throws Throwable {

      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.CONFIGURE_SYSLOG_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.INSTALL_VIB_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          UploadVibTaskService.UPLOAD_VIB_SCRIPT_NAME, true);

      createTestEnvironment(hostCount);
      startState.querySpecification = null;
      startState.deploymentServiceLink = TestHelper.createDeploymentService(testEnvironment).documentSelfLink;
      createHostEntities(mgmtHostCount, cloudHostCout, mixedHostCount, startState.deploymentServiceLink);

      for (String usageTage : Arrays.asList(UsageTag.MGMT.name(), UsageTag.CLOUD.name())) {
        startState.usageTag = usageTage;

        BulkProvisionHostsWorkflowService.State finalState =
            testEnvironment.callServiceAndWaitForState(
                BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
                startState,
                BulkProvisionHostsWorkflowService.State.class,
                (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

        TestHelper.assertTaskStateFinished(finalState.taskState);
      }
    }

    @Test(dataProvider = "HostCounts")
    public void testEndToEndSuccessWithVirtualNetwork(
        Integer hostCount,
        Integer mgmtHostCount,
        Integer cloudHostCout,
        Integer mixedHostCount) throws Throwable {
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.CONFIGURE_SYSLOG_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.INSTALL_VIB_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          UploadVibTaskService.UPLOAD_VIB_SCRIPT_NAME, true);
      createTestEnvironment(hostCount);
      startState.querySpecification = null;
      startState.deploymentServiceLink = TestHelper.createDeploymentService(testEnvironment, false, true)
          .documentSelfLink;
      createHostEntities(mgmtHostCount, cloudHostCout, mixedHostCount, startState.deploymentServiceLink);

      for (String usageTage : Arrays.asList(UsageTag.MGMT.name(), UsageTag.CLOUD.name())) {
        startState.usageTag = usageTage;

        BulkProvisionHostsWorkflowService.State finalState =
            testEnvironment.callServiceAndWaitForState(
                BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
                startState,
                BulkProvisionHostsWorkflowService.State.class,
                (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

        TestHelper.assertTaskStateFinished(finalState.taskState);
      }
    }

    @DataProvider(name = "SdnEnabled")
    public Object[][] getSdnEnabled() {
      return new Object[][]{
          {true},
          {false},
      };
    }

    @Test(dataProvider = "SdnEnabled")
    public void testEndToEndProvisionHostTaskFailed(Boolean sdnEnabled)
        throws Throwable {
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.CONFIGURE_SYSLOG_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.INSTALL_VIB_SCRIPT_NAME, false);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          UploadVibTaskService.UPLOAD_VIB_SCRIPT_NAME, false);
      MockHelper.mockProvisionAgent(agentControlClientFactory, hostClientFactory, true);
      createTestEnvironment(1);
      startState.querySpecification = null;
      startState.deploymentServiceLink =
          TestHelper.createDeploymentService(testEnvironment, false, sdnEnabled).documentSelfLink;
      createHostEntities(1, 2, 0, startState.deploymentServiceLink);

      for (String usageTage : Arrays.asList(UsageTag.MGMT.name(), UsageTag.CLOUD.name())) {
        startState.usageTag = usageTage;

        BulkProvisionHostsWorkflowService.State finalState =
            testEnvironment.callServiceAndWaitForState(
                BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
                startState,
                BulkProvisionHostsWorkflowService.State.class,
                (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

        assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
      }
    }

    @Test(enabled = false)
    public void testEndToEndFailNoMgmtHost() throws Throwable {
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.CONFIGURE_SYSLOG_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          ProvisionHostTaskService.INSTALL_VIB_SCRIPT_NAME, true);
      MockHelper.mockCreateScriptFile(deployerTestConfig.getDeployerContext(),
          UploadVibTaskService.UPLOAD_VIB_SCRIPT_NAME, true);
      MockHelper.mockProvisionAgent(agentControlClientFactory, hostClientFactory, true);
      createTestEnvironment(1);
      startState.deploymentServiceLink = TestHelper.createDeploymentService(testEnvironment, false, true)
          .documentSelfLink;
      createHostEntities(0, 2, 0, startState.deploymentServiceLink);

      BulkProvisionHostsWorkflowService.State finalState =
          testEnvironment.callServiceAndWaitForState(
              BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
              startState,
              BulkProvisionHostsWorkflowService.State.class,
              (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage));

      assertThat(finalState.taskState.stage, is(TaskState.TaskStage.FAILED));
    }
  }

  private static BulkProvisionHostsWorkflowService.State buildValidStartState(
      @Nullable TaskState.TaskStage stage) {

    BulkProvisionHostsWorkflowService.State startState = new BulkProvisionHostsWorkflowService.State();
    startState.deploymentServiceLink = "DEPLOYMENT_SERVICE_LINK";
    startState.usageTag = UsageTag.MGMT.name();
    startState.controlFlags = ControlFlags.CONTROL_FLAG_OPERATION_PROCESSING_DISABLED;

    if (stage != null) {
      startState.taskState = new TaskState();
      startState.taskState.stage = stage;
    }

    return startState;
  }
}
