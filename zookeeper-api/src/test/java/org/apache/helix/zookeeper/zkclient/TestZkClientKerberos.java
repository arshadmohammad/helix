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

import org.apache.helix.zookeeper.impl.ZkTestBase;
import org.apache.helix.zookeeper.impl.client.ZkClient;
import org.apache.helix.zookeeper.zkclient.serialize.BasicZkSerializer;
import org.apache.helix.zookeeper.zkclient.serialize.SerializableSerializer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.client.ZKClientConfig;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Integration tests for Kerberos authentication detection in ZkClient
 */
public class TestZkClientKerberos extends ZkTestBase {
  
  private ZkClient _zkClient;
  private Configuration _originalJaasConfig;

  @BeforeMethod
  public void setUp() {
    // Save original JAAS configuration
    _originalJaasConfig = Configuration.getConfiguration();
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
    // Create ZkClient with SASL disabled (default configuration)
    ZkClient.Builder builder = new ZkClient.Builder();
    builder.setZkServer(ZkTestBase.ZK_ADDR)
        .setMonitorRootPathOnly(false);
    _zkClient = builder.build();
    _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
    
    // Use reflection to call private isKerberosAuthEnabled method
    boolean result = invokeIsKerberosAuthEnabled(_zkClient);
    
    // Verify
    Assert.assertFalse(result, "isKerberosAuthEnabled should return false when SASL is disabled");
  }

  /**
   * Test isKerberosAuthEnabled returns true when SASL is enabled with Krb5LoginModule
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenSaslEnabledWithKerberos() throws Exception {
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
    
    // Enable SASL via system property
    System.setProperty("zookeeper.sasl.client", "true");
    
    try {
      // Create ZkClient without connecting (to avoid SASL auth issues with non-SASL ZK server)
      ZkClient.Builder builder = new ZkClient.Builder();
      builder.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false)
          .setConnectOnInit(false); // Don't connect during initialization
      _zkClient = builder.build();
      _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Use reflection to call private isKerberosAuthEnabled method
      boolean result = invokeIsKerberosAuthEnabled(_zkClient);
      
      // Verify
      Assert.assertTrue(result, "isKerberosAuthEnabled should return true when Krb5LoginModule is configured");
    } finally {
      System.clearProperty("zookeeper.sasl.client");
    }
  }

  /**
   * Test isKerberosAuthEnabled returns false when SASL is enabled but without Kerberos
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenSaslEnabledWithoutKerberos() throws Exception {
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
    
    // Enable SASL via system property
    System.setProperty("zookeeper.sasl.client", "true");
    
    try {
      // Create ZkClient
      ZkClient.Builder builder = new ZkClient.Builder();
      builder.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      _zkClient = builder.build();
      _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Use reflection to call private isKerberosAuthEnabled method
      boolean result = invokeIsKerberosAuthEnabled(_zkClient);
      
      // Verify
      Assert.assertFalse(result, "isKerberosAuthEnabled should return false when Kerberos is not configured");
    } finally {
      System.clearProperty("zookeeper.sasl.client");
    }
  }

  /**
   * Test isKerberosAuthEnabled with custom JAAS context name
   */
  @Test
  public void testIsKerberosAuthEnabled_WithCustomJaasContext() throws Exception {
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
    
    // Enable SASL and set custom context name
    System.setProperty("zookeeper.sasl.client", "true");
    System.setProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY, "CustomClient");
    
    try {
      // Create ZkClient
      ZkClient.Builder builder = new ZkClient.Builder();
      builder.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      _zkClient = builder.build();
      _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Use reflection to call private isKerberosAuthEnabled method
      boolean result = invokeIsKerberosAuthEnabled(_zkClient);
      
      // Verify
      Assert.assertTrue(result, "isKerberosAuthEnabled should work with custom JAAS context");
    } finally {
      System.clearProperty("zookeeper.sasl.client");
      System.clearProperty(ZKClientConfig.LOGIN_CONTEXT_NAME_KEY);
    }
  }

  /**
   * Test isKerberosAuthEnabled returns false when JAAS configuration is null
   */
  @Test
  public void testIsKerberosAuthEnabled_WhenJaasConfigNull() throws Exception {
    // Setup JAAS configuration that returns null
    Configuration jaasConfig = new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        return null;
      }
    };
    Configuration.setConfiguration(jaasConfig);
    
    // Enable SASL via system property
    System.setProperty("zookeeper.sasl.client", "true");
    
    try {
      // Create ZkClient
      ZkClient.Builder builder = new ZkClient.Builder();
      builder.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      _zkClient = builder.build();
      _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Use reflection to call private isKerberosAuthEnabled method
      boolean result = invokeIsKerberosAuthEnabled(_zkClient);
      
      // Verify
      Assert.assertFalse(result, "isKerberosAuthEnabled should return false when JAAS config is null");
    } finally {
      System.clearProperty("zookeeper.sasl.client");
    }
  }

  /**
   * Test isKerberosAuthEnabled with IBM JDK Krb5LoginModule
   */
  @Test
  public void testIsKerberosAuthEnabled_WithIBMKrb5LoginModule() throws Exception {
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
    
    // Enable SASL via system property
    System.setProperty("zookeeper.sasl.client", "true");
    
    try {
      // Create ZkClient
      ZkClient.Builder builder = new ZkClient.Builder();
      builder.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      _zkClient = builder.build();
      _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Use reflection to call private isKerberosAuthEnabled method
      boolean result = invokeIsKerberosAuthEnabled(_zkClient);
      
      // Verify
      Assert.assertTrue(result, "isKerberosAuthEnabled should detect IBM JDK Krb5LoginModule");
    } finally {
      System.clearProperty("zookeeper.sasl.client");
    }
  }

  /**
   * Test waitUntilConnected with Kerberos disabled
   * When Kerberos is disabled, waitUntilConnected should succeed with SyncConnected state
   */
  @Test
  public void testWaitUntilConnected_WithKerberosDisabled() throws Exception {
    // Create ZkClient with SASL disabled (default configuration)
    ZkClient.Builder builder = new ZkClient.Builder();
    builder.setZkServer(ZkTestBase.ZK_ADDR)
        .setMonitorRootPathOnly(false);
    _zkClient = builder.build();
    _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
    
    // Test waitUntilConnected - should succeed as client connects to real ZK
    boolean result = _zkClient.waitUntilConnected(5000, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertTrue(result, "waitUntilConnected should succeed when connected to ZK");
    
    // Verify isKerberosAuthEnabled is false
    boolean kerberosEnabled = invokeIsKerberosAuthEnabled(_zkClient);
    Assert.assertFalse(kerberosEnabled, "Kerberos should not be enabled");
  }

  /**
   * Test basic operations work with default (non-Kerberos) configuration
   */
  @Test
  public void testBasicOperations_WithKerberosDisabled() throws Exception {
    // Create ZkClient with SASL disabled
    ZkClient.Builder builder = new ZkClient.Builder();
    builder.setZkServer(ZkTestBase.ZK_ADDR)
        .setMonitorRootPathOnly(false);
    _zkClient = builder.build();
    _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
    
    // Wait for connection
    boolean connected = _zkClient.waitUntilConnected(5000, TimeUnit.MILLISECONDS);
    Assert.assertTrue(connected, "Should connect to ZK");
    
    // Test basic CRUD operations
    String testPath = "/testKerberosDisabled";
    _zkClient.create(testPath, "testData", CreateMode.PERSISTENT);
    
    String data = _zkClient.readData(testPath);
    Assert.assertEquals(data, "testData", "Should read correct data");
    
    _zkClient.writeData(testPath, "updatedData");
    data = _zkClient.readData(testPath);
    Assert.assertEquals(data, "updatedData", "Should read updated data");
    
    _zkClient.delete(testPath);
    Assert.assertFalse(_zkClient.exists(testPath), "Node should be deleted");
  }

  /**
   * Test waitForEstablishedSession with default configuration
   */
  @Test
  public void testWaitForEstablishedSession_WithKerberosDisabled() throws Exception {
    // Create ZkClient with SASL disabled
    ZkClient.Builder builder = new ZkClient.Builder();
    builder.setZkServer(ZkTestBase.ZK_ADDR)
        .setMonitorRootPathOnly(false);
    _zkClient = builder.build();
    _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
    
    // Test waitForEstablishedSession
    long sessionId = _zkClient.waitForEstablishedSession(5000, TimeUnit.MILLISECONDS);
    
    // Verify
    Assert.assertTrue(sessionId > 0, "Should return valid session ID");
  }

  /**
   * Test that multiple clients can be created with different configurations
   */
  @Test
  public void testMultipleClientsWithDifferentConfigurations() throws Exception {
    ZkClient client1 = null;
    ZkClient client2 = null;
    
    try {
      // Create first client with default configuration
      ZkClient.Builder builder1 = new ZkClient.Builder();
      builder1.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      client1 = builder1.build();
      client1.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Create second client with default configuration
      ZkClient.Builder builder2 = new ZkClient.Builder();
      builder2.setZkServer(ZkTestBase.ZK_ADDR)
          .setMonitorRootPathOnly(false);
      client2 = builder2.build();
      client2.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
      
      // Both should connect successfully
      Assert.assertTrue(client1.waitUntilConnected(5000, TimeUnit.MILLISECONDS));
      Assert.assertTrue(client2.waitUntilConnected(5000, TimeUnit.MILLISECONDS));
      
      // Test that they can interact with ZK independently
      String path1 = "/testMultiClient1";
      String path2 = "/testMultiClient2";
      
      client1.create(path1, "data1", CreateMode.PERSISTENT);
      client2.create(path2, "data2", CreateMode.PERSISTENT);
      
      Assert.assertEquals(client1.readData(path1), "data1");
      Assert.assertEquals(client2.readData(path2), "data2");
      
      // Cleanup
      client1.delete(path1);
      client2.delete(path2);
      
    } finally {
      if (client1 != null && !client1.isClosed()) {
        client1.close();
      }
      if (client2 != null && !client2.isClosed()) {
        client2.close();
      }
    }
  }

  /**
   * Test connection timeout behavior
   */
  @Test
  public void testConnectionTimeout() throws Exception {
    // Create ZkClient with very short timeout
    ZkClient.Builder builder = new ZkClient.Builder();
    builder.setZkServer(ZkTestBase.ZK_ADDR)
        .setMonitorRootPathOnly(false)
        .setConnectionTimeout(10000); // 10 seconds should be enough for local ZK
    _zkClient = builder.build();
    _zkClient.setZkSerializer(new BasicZkSerializer(new SerializableSerializer()));
    
    // Should connect within timeout
    boolean result = _zkClient.waitUntilConnected(15000, TimeUnit.MILLISECONDS);
    Assert.assertTrue(result, "Should connect within timeout");
  }

  // Helper methods

  /**
   * Use reflection to invoke private isKerberosAuthEnabled method
   */
  private boolean invokeIsKerberosAuthEnabled(ZkClient zkClient) throws Exception {
    // The method is in the base class org.apache.helix.zookeeper.zkclient.ZkClient
    Method method = org.apache.helix.zookeeper.zkclient.ZkClient.class.getDeclaredMethod("isKerberosAuthEnabled");
    method.setAccessible(true);
    return (Boolean) method.invoke(zkClient);
  }
}
