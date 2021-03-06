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
package org.apache.accumulo.test.randomwalk.security;

import java.util.Properties;
import java.util.Random;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.security.tokens.InstanceTokenWrapper;
import org.apache.accumulo.core.security.tokens.UserPassToken;
import org.apache.accumulo.test.randomwalk.State;
import org.apache.accumulo.test.randomwalk.Test;

public class ChangePass extends Test {
  
  @Override
  public void visit(State state, Properties props) throws Exception {
    String target = props.getProperty("target");
    String source = props.getProperty("source");
    
    InstanceTokenWrapper auth;
    if (source.equals("system")) {
      auth = WalkingSecurity.get(state).getSysAuthInfo();
    } else {
      auth = WalkingSecurity.get(state).getTabAuthInfo();
    }
    Connector conn = state.getInstance().getConnector(auth);
        
    boolean hasPerm;
    boolean targetExists;
    if (target.equals("table")) {
      target = WalkingSecurity.get(state).getTabUserName();
    } else
      target = WalkingSecurity.get(state).getSysUserName();
    
    targetExists = WalkingSecurity.get(state).userExists(target);
      
    hasPerm = WalkingSecurity.get(state).canChangePassword(auth, target);
    
    Random r = new Random();
    
    byte[] newPass = new byte[r.nextInt(50) + 1];
    for (int i =0; i < newPass.length; i++)
      newPass[i] = (byte) ((r.nextInt(26)+65) & 0xFF);
    
    try {
      conn.securityOperations().changeUserPassword(target, newPass);
    } catch (AccumuloSecurityException ae) {
      switch (ae.getErrorCode()) {
        case PERMISSION_DENIED:
          if (hasPerm)
            throw new AccumuloException("Change failed when it should have succeeded to change " + target + "'s password", ae);
          return;
        case USER_DOESNT_EXIST:
          if (targetExists)
            throw new AccumuloException("User " + target + " doesn't exist and they SHOULD.", ae);
          return;
        case BAD_CREDENTIALS:
          if (!WalkingSecurity.get(state).userPassTransient(conn.whoami()))
            throw new AccumuloException("Bad credentials for user " + conn.whoami());
          return;
        default:
          throw new AccumuloException("Got unexpected exception", ae);
      }
    }
    WalkingSecurity.get(state).changePassword(new UserPassToken(target, newPass));
    if (!hasPerm)
      throw new AccumuloException("Password change succeeded when it should have failed for " + source + " changing the password for " + target + ".");
  }
}
