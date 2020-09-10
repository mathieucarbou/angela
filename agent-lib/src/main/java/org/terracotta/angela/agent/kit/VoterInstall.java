/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.agent.kit;

import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class VoterInstall {
  private final Distribution distribution;
  private final File kitLocation;
  private final File workingDir;
  private final SecurityRootDirectory securityRootDirectory;
  private final TerracottaCommandLineEnvironment tcEnv;
  private final Map<String, TerracottaVoterInstance> terracottaVoterInstances = new HashMap<>();
  
  public VoterInstall(Distribution distribution, File kitLocation, File workingDir, SecurityRootDirectory securityRootDirectory,
                      TerracottaCommandLineEnvironment tcEnv) {
    this.distribution = distribution;
    this.kitLocation = kitLocation;
    this.workingDir = workingDir;
    this.securityRootDirectory = securityRootDirectory;
    this.tcEnv = tcEnv;
  }

  public File getKitLocation() {
    return kitLocation;
  }
  
  public TerracottaVoterInstance getTerracottaVoterInstance(TerracottaVoter terracottaVoter) {
    synchronized (terracottaVoterInstances) {
      return terracottaVoterInstances.get(terracottaVoter.getId());
    }
  }

  public void addVoter(TerracottaVoter terracottaVoter) {
    synchronized (terracottaVoterInstances) {
      TerracottaVoterInstance terracottaVoterInstance = new TerracottaVoterInstance(terracottaVoter,
          distribution.createDistributionController(), kitLocation, workingDir, securityRootDirectory, tcEnv);
      terracottaVoterInstances.put(terracottaVoter.getId(), terracottaVoterInstance);
    }
  }

  public int removeVoter(TerracottaVoter terracottaVoter) {
    synchronized (terracottaVoterInstances) {
      terracottaVoterInstances.remove(terracottaVoter.getId());
      return terracottaVoterInstances.size();
    }
  }
  
  public int terracottaVoterInstanceCount() {
    synchronized (terracottaVoterInstances) {
      return terracottaVoterInstances.size();
    }
  }
  
}
