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
package org.apache.accumulo.server.security.handler;

import java.util.Set;
import java.util.TreeSet;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.security.thrift.SecurityErrorCode;
import org.apache.accumulo.core.security.tokens.AccumuloToken;
import org.apache.accumulo.core.security.tokens.InstanceTokenWrapper;
import org.apache.accumulo.core.security.tokens.UserPassToken;
import org.apache.accumulo.fate.zookeeper.IZooReaderWriter;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.accumulo.server.zookeeper.ZooCache;
import org.apache.accumulo.server.zookeeper.ZooReaderWriter;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;

// Utility class for adding all authentication info into ZK
public final class ZKAuthenticator implements Authenticator {
  static final Logger log = Logger.getLogger(ZKAuthenticator.class);
  private static Authenticator zkAuthenticatorInstance = null;
  
  private String ZKUserPath;
  private final ZooCache zooCache;
  
  public static synchronized Authenticator getInstance() {
    if (zkAuthenticatorInstance == null)
      zkAuthenticatorInstance = new ZKAuthenticator();
    return zkAuthenticatorInstance;
  }
  
  public ZKAuthenticator() {
    zooCache = new ZooCache();
  }
  
  public void initialize(String instanceId, boolean initialize) {
    ZKUserPath = Constants.ZROOT + "/" + instanceId + "/users";
  }
  
  @Override
  public void initializeSecurity(InstanceTokenWrapper credentials, AccumuloToken<?,?> token) throws AccumuloSecurityException {
    if (!(token instanceof UserPassToken))
      throw new AccumuloSecurityException("ZKAuthenticator doesn't take this token type", SecurityErrorCode.INVALID_TOKEN);
    UserPassToken upt = (UserPassToken) token;
    try {
      // remove old settings from zookeeper first, if any
      IZooReaderWriter zoo = ZooReaderWriter.getRetryingInstance();
      synchronized (zooCache) {
        zooCache.clear();
        if (zoo.exists(ZKUserPath)) {
          zoo.recursiveDelete(ZKUserPath, NodeMissingPolicy.SKIP);
          log.info("Removed " + ZKUserPath + "/" + " from zookeeper");
        }
        
        // prep parent node of users with root username
        zoo.putPersistentData(ZKUserPath, upt.getPrincipal().getBytes(), NodeExistsPolicy.FAIL);
        
        constructUser(upt.getPrincipal(), ZKSecurityTool.createPass(upt.getPassword()));
      }
    } catch (KeeperException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (AccumuloException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Sets up the user in ZK for the provided user. No checking for existence is done here, it should be done before calling.
   */
  private void constructUser(String user, byte[] pass) throws KeeperException, InterruptedException {
    synchronized (zooCache) {
      zooCache.clear();
      IZooReaderWriter zoo = ZooReaderWriter.getRetryingInstance();
      zoo.putPrivatePersistentData(ZKUserPath + "/" + user, pass, NodeExistsPolicy.FAIL);
    }
  }
  
  @Override
  public Set<String> listUsers() {
    return new TreeSet<String>(zooCache.getChildren(ZKUserPath));
  }
  
  /**
   * Creates a user with no permissions whatsoever
   */
  @Override
  public void createUser(AccumuloToken<?,?> token) throws AccumuloSecurityException {
    if (!(token instanceof UserPassToken))
      throw new AccumuloSecurityException("ZKAuthenticator doesn't take this token type", SecurityErrorCode.INVALID_TOKEN);
    UserPassToken upt = (UserPassToken) token;
    try {
      constructUser(upt.getPrincipal(), ZKSecurityTool.createPass(upt.getPassword()));
    } catch (KeeperException e) {
      if (e.code().equals(KeeperException.Code.NODEEXISTS))
        throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.USER_EXISTS, e);
      throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (AccumuloException e) {
      log.error(e, e);
      throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.DEFAULT_SECURITY_ERROR, e);
    }
  }
  
  @Override
  public void dropUser(String user) throws AccumuloSecurityException {
    try {
      synchronized (zooCache) {
        zooCache.clear();
        ZooReaderWriter.getRetryingInstance().recursiveDelete(ZKUserPath + "/" + user, NodeMissingPolicy.FAIL);
      }
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (KeeperException e) {
      if (e.code().equals(KeeperException.Code.NONODE))
        throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST, e);
      log.error(e, e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    }
  }
  
  @Override
  public void changePassword(AccumuloToken<?,?> token) throws AccumuloSecurityException {
    if (!(token instanceof UserPassToken))
      throw new AccumuloSecurityException("ZKAuthenticator doesn't take this token type", SecurityErrorCode.INVALID_TOKEN);
    UserPassToken upt = (UserPassToken) token;
    if (userExists(upt.getPrincipal())) {
      try {
        synchronized (zooCache) {
          zooCache.clear(ZKUserPath + "/" + upt.getPrincipal());
          ZooReaderWriter.getRetryingInstance().putPrivatePersistentData(ZKUserPath + "/" + upt.getPrincipal(), ZKSecurityTool.createPass(upt.getPassword()), NodeExistsPolicy.OVERWRITE);
        }
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      } catch (AccumuloException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.DEFAULT_SECURITY_ERROR, e);
      }
    } else
      throw new AccumuloSecurityException(upt.getPrincipal(), SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  /**
   * Checks if a user exists
   */
  @Override
  public boolean userExists(String user) {
    return zooCache.get(ZKUserPath + "/" + user) != null;
  }
  
  @Override
  public boolean validSecurityHandlers(Authorizor auth, PermissionHandler pm) {
    return true;
  }
  
  @Override
  public boolean authenticateUser(AccumuloToken<?,?> token) throws AccumuloSecurityException {
    if (!(token instanceof UserPassToken))
      throw new AccumuloSecurityException("ZKAuthenticator doesn't take this token type", SecurityErrorCode.INVALID_TOKEN);
    UserPassToken upt = (UserPassToken) token;
    byte[] pass;
    String zpath = ZKUserPath + "/" + upt.getPrincipal();
    pass = zooCache.get(zpath);
    boolean result = ZKSecurityTool.checkPass(upt.getPassword(), pass);
    if (!result) {
      zooCache.clear(zpath);
      pass = zooCache.get(zpath);
      result = ZKSecurityTool.checkPass(upt.getPassword(), pass);
    }
    return result;
  }

  @Override
  public String getTokenClassName() {
    return UserPassToken.class.getName();
  }
}
