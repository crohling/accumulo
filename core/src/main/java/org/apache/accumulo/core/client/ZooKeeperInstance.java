/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.impl.ConnectorImpl;
import org.apache.accumulo.core.client.impl.MasterClient;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.file.FileUtil;
import org.apache.accumulo.core.master.thrift.MasterClientService.Client;
import org.apache.accumulo.core.security.thrift.AuthInfo;
import org.apache.accumulo.core.security.thrift.ThriftSecurityException;
import org.apache.accumulo.core.security.tokens.AccumuloToken;
import org.apache.accumulo.core.security.tokens.InstanceTokenWrapper;
import org.apache.accumulo.core.security.tokens.UserPassToken;
import org.apache.accumulo.core.util.ArgumentChecker;
import org.apache.accumulo.core.util.CachedConfiguration;
import org.apache.accumulo.core.util.OpTimer;
import org.apache.accumulo.core.util.TextUtil;
import org.apache.accumulo.core.util.ThriftUtil;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

/**
 * <p>
 * An implementation of instance that looks in zookeeper to find information needed to connect to an instance of accumulo.
 * 
 * <p>
 * The advantage of using zookeeper to obtain information about accumulo is that zookeeper is highly available, very responsive, and supports caching.
 * 
 * <p>
 * Because it is possible for multiple instances of accumulo to share a single set of zookeeper servers, all constructors require an accumulo instance name.
 * 
 * If you do not know the instance names then run accumulo org.apache.accumulo.server.util.ListInstances on an accumulo server.
 * 
 */

@SuppressWarnings("deprecation")
public class ZooKeeperInstance implements Instance {
  
  private static final Logger log = Logger.getLogger(ZooKeeperInstance.class);
  
  private String instanceId = null;
  private String instanceName = null;
  
  private ZooCache zooCache;
  
  private String zooKeepers;
  
  private int zooKeepersSessionTimeOut;
  
  /**
   * 
   * @param instanceName
   *          The name of specific accumulo instance. This is set at initialization time.
   * @param zooKeepers
   *          A comma separated list of zoo keeper server locations. Each location can contain an optional port, of the format host:port.
   */
  
  public ZooKeeperInstance(String instanceName, String zooKeepers) {
    this(instanceName, zooKeepers, (int) AccumuloConfiguration.getDefaultConfiguration().getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT));
  }
  
  /**
   * 
   * @param instanceName
   *          The name of specific accumulo instance. This is set at initialization time.
   * @param zooKeepers
   *          A comma separated list of zoo keeper server locations. Each location can contain an optional port, of the format host:port.
   * @param sessionTimeout
   *          zoo keeper session time out in milliseconds.
   */
  
  public ZooKeeperInstance(String instanceName, String zooKeepers, int sessionTimeout) {
    ArgumentChecker.notNull(instanceName, zooKeepers);
    this.instanceName = instanceName;
    this.zooKeepers = zooKeepers;
    this.zooKeepersSessionTimeOut = sessionTimeout;
    zooCache = ZooCache.getInstance(zooKeepers, sessionTimeout);
    getInstanceID();
  }
  
  /**
   * 
   * @param instanceId
   *          The UUID that identifies the accumulo instance you want to connect to.
   * @param zooKeepers
   *          A comma separated list of zoo keeper server locations. Each location can contain an optional port, of the format host:port.
   */
  
  public ZooKeeperInstance(UUID instanceId, String zooKeepers) {
    this(instanceId, zooKeepers, (int) AccumuloConfiguration.getDefaultConfiguration().getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT));
  }
  
  /**
   * 
   * @param instanceId
   *          The UUID that identifies the accumulo instance you want to connect to.
   * @param zooKeepers
   *          A comma separated list of zoo keeper server locations. Each location can contain an optional port, of the format host:port.
   * @param sessionTimeout
   *          zoo keeper session time out in milliseconds.
   */
  
  public ZooKeeperInstance(UUID instanceId, String zooKeepers, int sessionTimeout) {
    ArgumentChecker.notNull(instanceId, zooKeepers);
    this.instanceId = instanceId.toString();
    this.zooKeepers = zooKeepers;
    this.zooKeepersSessionTimeOut = sessionTimeout;
    zooCache = ZooCache.getInstance(zooKeepers, sessionTimeout);
  }
  
  @Override
  public String getInstanceID() {
    if (instanceId == null) {
      // want the instance id to be stable for the life of this instance object,
      // so only get it once
      String instanceNamePath = Constants.ZROOT + Constants.ZINSTANCES + "/" + instanceName;
      byte[] iidb = zooCache.get(instanceNamePath);
      if (iidb == null) {
        throw new RuntimeException("Instance name " + instanceName
            + " does not exist in zookeeper.  Run \"accumulo org.apache.accumulo.server.util.ListInstances\" to see a list.");
      }
      instanceId = new String(iidb);
    }
    
    if (zooCache.get(Constants.ZROOT + "/" + instanceId) == null) {
      if (instanceName == null)
        throw new RuntimeException("Instance id " + instanceId + " does not exist in zookeeper");
      throw new RuntimeException("Instance id " + instanceId + " pointed to by the name " + instanceName + " does not exist in zookeeper");
    }
    
    return instanceId;
  }
  
  @Override
  public List<String> getMasterLocations() {
    String masterLocPath = ZooUtil.getRoot(this) + Constants.ZMASTER_LOCK;
    
    OpTimer opTimer = new OpTimer(log, Level.TRACE).start("Looking up master location in zoocache.");
    byte[] loc = ZooUtil.getLockData(zooCache, masterLocPath);
    opTimer.stop("Found master at " + (loc == null ? null : new String(loc)) + " in %DURATION%");
    
    if (loc == null) {
      return Collections.emptyList();
    }
    
    return Collections.singletonList(new String(loc));
  }
  
  @Override
  public String getRootTabletLocation() {
    String zRootLocPath = ZooUtil.getRoot(this) + Constants.ZROOT_TABLET_LOCATION;
    
    OpTimer opTimer = new OpTimer(log, Level.TRACE).start("Looking up root tablet location in zookeeper.");
    byte[] loc = zooCache.get(zRootLocPath);
    opTimer.stop("Found root tablet at " + (loc == null ? null : new String(loc)) + " in %DURATION%");
    
    if (loc == null) {
      return null;
    }
    
    return new String(loc).split("\\|")[0];
  }
  
  @Override
  public String getInstanceName() {
    if (instanceName == null)
      instanceName = lookupInstanceName(zooCache, UUID.fromString(getInstanceID()));
    
    return instanceName;
  }
  
  @Override
  public String getZooKeepers() {
    return zooKeepers;
  }
  
  @Override
  public int getZooKeepersSessionTimeOut() {
    return zooKeepersSessionTimeOut;
  }
  
  /**
   * @deprecated @since 1.5, use {@link #getConnector(AccumuloToken)}
   */
  @Override
  public Connector getConnector(String user, CharSequence pass) throws AccumuloException, AccumuloSecurityException {
    return getConnector(user, TextUtil.getBytes(new Text(pass.toString())));
  }
  
  /**
   * @deprecated @since 1.5, use {@link #getConnector(AccumuloToken)}
   */
  @Override
  public Connector getConnector(String user, ByteBuffer pass) throws AccumuloException, AccumuloSecurityException {
    return getConnector(new UserPassToken(user, pass));
  }
  
  // Suppress deprecation, ConnectorImpl is deprecated to warn clients against using.
  /**
   * @deprecated @since 1.5, use {@link #getConnector(AccumuloToken)}
   */
  @Override
  public Connector getConnector(String user, byte[] pass) throws AccumuloException, AccumuloSecurityException {
    return getConnector(user, ByteBuffer.wrap(pass));
  }
  
  private AccumuloConfiguration conf = null;
  
  @Override
  public AccumuloConfiguration getConfiguration() {
    if (conf == null)
      conf = AccumuloConfiguration.getDefaultConfiguration();
    return conf;
  }
  
  @Override
  public void setConfiguration(AccumuloConfiguration conf) {
    this.conf = conf;
  }
  
  /**
   * @deprecated Use {@link #lookupInstanceName(org.apache.accumulo.fate.zookeeper.ZooCache, UUID)} instead
   */
  @Deprecated
  public static String lookupInstanceName(org.apache.accumulo.core.zookeeper.ZooCache zooCache, UUID instanceId) {
    return lookupInstanceName((ZooCache) zooCache, instanceId);
  }
  
  /**
   * Given a zooCache and instanceId, look up the instance name.
   * 
   * @param zooCache
   * @param instanceId
   * @return the instance name
   */
  public static String lookupInstanceName(ZooCache zooCache, UUID instanceId) {
    ArgumentChecker.notNull(zooCache, instanceId);
    for (String name : zooCache.getChildren(Constants.ZROOT + Constants.ZINSTANCES)) {
      String instanceNamePath = Constants.ZROOT + Constants.ZINSTANCES + "/" + name;
      UUID iid = UUID.fromString(new String(zooCache.get(instanceNamePath)));
      if (iid.equals(instanceId)) {
        return name;
      }
    }
    return null;
  }
  
  // To be moved to server code. Only lives here to support the Accumulo Shell
  @Deprecated
  public static String getInstanceIDFromHdfs(Path instanceDirectory) {
    try {
      FileSystem fs = FileUtil.getFileSystem(CachedConfiguration.getInstance(), AccumuloConfiguration.getSiteConfiguration());
      FileStatus[] files = fs.listStatus(instanceDirectory);
      log.debug("Trying to read instance id from " + instanceDirectory);
      if (files == null || files.length == 0) {
        log.error("unable obtain instance id at " + instanceDirectory);
        throw new RuntimeException("Accumulo not initialized, there is no instance id at " + instanceDirectory);
      } else if (files.length != 1) {
        log.error("multiple potential instances in " + instanceDirectory);
        throw new RuntimeException("Accumulo found multiple possible instance ids in " + instanceDirectory);
      } else {
        String result = files[0].getPath().getName();
        return result;
      }
    } catch (IOException e) {
      throw new RuntimeException("Accumulo not initialized, there is no instance id at " + instanceDirectory, e);
    }
  }
  
  /**
   * @deprecated @since 1.5, use {@link #getConnector(AccumuloToken)}
   */
  @Override
  public Connector getConnector(AuthInfo auth) throws AccumuloException, AccumuloSecurityException {
    return getConnector(auth.user, auth.password);
  }
  
  // Suppress deprecation, ConnectorImpl is deprecated to warn clients against using.
  @Override
  public Connector getConnector(AccumuloToken<?,?> token) throws AccumuloException, AccumuloSecurityException {
    return new ConnectorImpl(this, token);
  }
  
  // Suppress deprecation, ConnectorImpl is deprecated to warn clients against using.
  @Override
  public Connector getConnector(InstanceTokenWrapper token) throws AccumuloException, AccumuloSecurityException {
    return getConnector(token.getToken());
  }
  
  @Override
  public String getSecurityTokenClass() throws AccumuloException {
    Client client = null;
    try {
      client = MasterClient.getConnectionWithRetry(this);
      return client.getSecurityTokenClass();
    } catch (TTransportException e) {
      throw new AccumuloException(e);
    } catch (ThriftSecurityException e) {
      throw new AccumuloException(e);
    } catch (TException e) {
      throw new AccumuloException(e);
    } finally {
      if (client != null) {
        ThriftUtil.returnClient(client);
      }
    }
  }
}
