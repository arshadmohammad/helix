package org.apache.helix.zookeeper.zkclient;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;

import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for Kerberos authentication detection in ZkClient
 */
public class TestZkClientKerberos {
  
  private TestableZkClient _zkClient;
  private ZkConnection _mockZkConnection;
  private ZooKeeper _mockZooKeeper;
  private ZKClientConfig _mockZkClientConfig;
  private Configuration _originalJaasConfig;

  @BeforeMethod
  public void setUp() {
    // Save original JAAS configuration
    _originalJaasConfig = Configuration.getConfiguration();
    
    // Create mocks
    _mockZkConnection = mock(ZkConnection.class);
    _mockZooKeeper = mock(ZooKeeper.class);
    _mockZkClientConfig = mock(ZKClientConfig.class);
    
    // Setup basic mock behavior
    when(_mockZkConnection.getServers()).thenReturn("localhost:2181");
    when(_mockZkConnection.getZookeeper()).thenReturn(_mockZooKeeper);
    when(_mockZooKeeper.getClientConfig()).thenReturn(_mockZkClientConfig);
  }

  @AfterMethod
  public void tearDown() {
    // Restore original JAAS configuration
    if (_originalJaasConfig != null) {
      Configuration.setConfiguration(_originalJaasConfig);
    }
    
    if (_zkClient != null && !_zkClient.isClosed()) {
      _zkClient.close();
    }
  }

  /**
   * Test isKerberosAuthEnabled returns false when SASL is disabled
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenSaslDisabled() throws Exception {
    // Setup: SASL is disabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(false);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertFalse(result, "isKerberosAuthEnabled should return false when SASL is disabled");
    verify(_mockZkClientConfig).isSaslClientEnabled();
  }

  /**
   * Test isKerberosAuthEnabled returns true when SASL is enabled with Krb5LoginModule
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenSaslEnabledWithKerberos() throws Exception {
    // Setup: SASL is enabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    
    // Setup JAAS configuration with Krb5LoginModule
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT.equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.sun.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertTrue(result, "isKerberosAuthEnabled should return true when Krb5LoginModule is configured");
    verify(_mockZkClientConfig).isSaslClientEnabled();
    verify(_mockZkClientConfig).getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
  }

  /**
   * Test isKerberosAuthEnabled returns false when SASL is enabled but without Kerberos
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenSaslEnabledWithoutKerberos() throws Exception {
    // Setup: SASL is enabled but with non-Kerberos module
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    
    // Setup JAAS configuration with non-Kerberos module
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT.equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.example.PlainLoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertFalse(result, "isKerberosAuthEnabled should return false when Kerberos is not configured");
    verify(_mockZkClientConfig).isSaslClientEnabled();
  }

  /**
   * Test isKerberosAuthEnabled with custom JAAS context name
   */
  @Test
  public void testIsKerberosAuthEnabled_WithCustomJaasContext() throws Exception {
    // Setup: SASL is enabled with custom context name
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn("CustomClient");
    
    // Setup JAAS configuration with custom context
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if ("CustomClient".equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.sun.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertTrue(result, "isKerberosAuthEnabled should work with custom JAAS context");
    verify(_mockZkClientConfig).getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
  }

  /**
   * Test isKerberosAuthEnabled returns false when JAAS configuration is null
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenJaasConfigNull() throws Exception {
    // Setup: SASL is enabled but JAAS config returns null
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    
    // Setup JAAS configuration that returns null
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertFalse(result, "isKerberosAuthEnabled should return false when JAAS config is null");
  }

  /**
   * Test isKerberosAuthEnabled with IBM JDK Krb5LoginModule
   */
  @Test
  public void testIsKerberosAuthEnabled_WithIBMKrb5LoginModule() throws Exception {
    // Setup: SASL is enabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    
    // Setup JAAS configuration with IBM Krb5LoginModule
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT.equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.ibm.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertTrue(result, "isKerberosAuthEnabled should detect IBM JDK Krb5LoginModule");
  }

  /**
   * Test waitUntilConnected waits for SaslAuthenticated when Kerberos is enabled
   */
  @Test
  public void testWaitUntilConnected_WithKerberosEnabled() throws Exception {
    // Setup: Kerberos is enabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT.equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.sun.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Set current state to SaslAuthenticated
    _zkClient.setCurrentState(KeeperState.SaslAuthenticated);
    
    // Test waitUntilConnected
    boolean result = _zkClient.waitUntilConnected(100, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertTrue(result, "waitUntilConnected should succeed when SaslAuthenticated");
  }

  /**
   * Test waitUntilConnected waits for SyncConnected when Kerberos is disabled
   */
  @Test
  public void testWaitUntilConnected_WithKerberosDisabled() throws Exception {
    // Setup: Kerberos is disabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(false);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Set current state to SyncConnected
    _zkClient.setCurrentState(KeeperState.SyncConnected);
    
    // Test waitUntilConnected
    boolean result = _zkClient.waitUntilConnected(100, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertTrue(result, "waitUntilConnected should succeed when SyncConnected");
  }

  /**
   * Test waitForEstablishedSession uses waitUntilConnected
   */
  @Test
  public void testWaitForEstablishedSession_UsesWaitUntilConnected() throws Exception {
    // Setup: Kerberos is disabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(false);
    when(_mockZooKeeper.getSessionId()).thenReturn(12345L);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Set current state to SyncConnected
    _zkClient.setCurrentState(KeeperState.SyncConnected);
    
    // Test waitForEstablishedSession
    long sessionId = _zkClient.waitForEstablishedSession(100, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertEquals(sessionId, 12345L, "waitForEstablishedSession should return session ID");
    verify(_mockZooKeeper).getSessionId();
  }

  /**
   * Test waitForEstablishedSession with Kerberos enabled
   */
  @Test
  public void testWaitForEstablishedSession_WithKerberos() throws Exception {
    // Setup: Kerberos is enabled
    when(_mockZkClientConfig.isSaslClientEnabled()).thenReturn(true);
    when(_mockZkClientConfig.getProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT))
        .thenReturn(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT);
    when(_mockZooKeeper.getSessionId()).thenReturn(67890L);
    
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        if (ZKClientConfig.LOGIN_CONTEXT_NAME_KEY_DEFAULT.equals(name)) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry(
                  "com.sun.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  new HashMap<>()
              )
          };
        }
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Create ZkClient with mocked connection
    _zkClient = createTestZkClient(_mockZkConnection);
    
    // Set current state to SaslAuthenticated
    _zkClient.setCurrentState(KeeperState.SaslAuthenticated);
    
    // Test waitForEstablishedSession
    long sessionId = _zkClient.waitForEstablishedSession(100, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertEquals(sessionId, 67890L, "waitForEstablishedSession should work with Kerberos");
    verify(_mockZooKeeper).getSessionId();
  }

  // Helper methods

  /**
   * Create a test ZkClient with mocked connection
   */
  private TestableZkClient createTestZkClient(ZkConnection mockZkConnection) {
    return new TestableZkClient(mockZkConnection);
  }

  /**
   * Use reflection to invoke private isKerberosAuthEnabled method
   */
  private boolean invokeIsKerberosAuthEnabled(TestableZkClient zkClient) throws Exception {
    return zkClient.testIsKerberosAuthEnabled();
  }
  
  /**
   * Testable subclass of ZkClient that exposes protected/private methods for testing
   */
  private static class TestableZkClient extends ZkClient {
    public TestableZkClient(IZkConnection zkConnection) {
      super(zkConnection, Integer.MAX_VALUE, 10000, null, null, null, null, false);
    }
    
    /**
     * Expose isKerberosAuthEnabled for testing
     */
    public boolean testIsKerberosAuthEnabled() {
      try {
        Method method = ZkClient.class.getDeclaredMethod("isKerberosAuthEnabled");
        method.setAccessible(true);
        return (Boolean) method.invoke(this);
      } catch (Exception e) {
        throw new RuntimeException("Failed to invoke isKerberosAuthEnabled", e);
      }
    }
  }
}
