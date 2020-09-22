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

package org.terracotta.angela.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.util.IpUtils;

import java.nio.file.Paths;

/**
 * Listing of all system properties supported in angela
 */
public enum AngelaProperties {
  // root dir where Angela puts installation, work directories and any file that is needed
  ROOT_DIR("angela.rootDir", Paths.get("/data/angela").toAbsolutePath().toString()),
  // shortcut for angela.rootDir property above
  KITS_DIR("kitsDir", Paths.get("/data/angela").toAbsolutePath().toString()),

  // use this property to use a local build instead of downloading a kit build
  KIT_INSTALLATION_DIR("angela.kitInstallationDir", null),

  // same as angela.kitInstallationDir, used for compatibility with Galvan
  KIT_INSTALLATION_PATH("kitInstallationPath", null),

  // running the test offline (no network connection)
  OFFLINE("angela.offline", "false"),

  DISTRIBUTION("angela.distribution", null),

  // display Ignite logging (used to help debugging the behaviour of Angela)
  IGNITE_LOGGING("angela.igniteLogging", "false"),

  // do not clean work directory (used to have access to logs after end of test for debugging test issues)
  SKIP_UNINSTALL("angela.skipUninstall", "false"),

  // forces a kit copy instead of using a common kit install for multiple tests. useful for parallel execution of tests
  // that changes files in the kit install (e.g. tmc.properties)
  KIT_COPY("angela.kitCopy", "false"),

  // ssh properties
  SSH_USERNAME("angela.ssh.userName", System.getProperty("user.name")),
  SSH_USERNAME_KEY_PATH("angela.ssh.userName.keyPath", null),
  SSH_STRICT_HOST_CHECKING("angela.ssh.strictHostKeyChecking", "true"),

  // logging properties
  TMS_FULL_LOGGING("angela.tms.fullLogging", "false"),
  TSA_FULL_LOGGING("angela.tsa.fullLogging", "false"),
  VOTER_FULL_LOGGING("angela.voter.fullLogging", "false"),

  // jdk properties to be used by Angela for running processes
  JAVA_VENDOR("angela.java.vendor", "zulu"),
  JAVA_VERSION("angela.java.version", "1.8"),
  JAVA_OPTS("angela.java.opts", "-Djdk.security.allowNonCaAnchor=false"),

  // internal properties
  DIRECT_JOIN("angela.directJoin", ""),
  NODE_NAME("angela.nodeName", IpUtils.getHostName()),
  ;

  private static final Logger logger = LoggerFactory.getLogger(AngelaProperties.class);

  AngelaProperties(String propertyName, String defaultValue) {
    this.propertyName = propertyName;
    this.defaultValue = defaultValue;
  }

  private final String propertyName;
  private final String defaultValue;

  public String getDefaultValue() {
    return defaultValue;
  }

  public String getSpecifiedValue() {
    return System.getProperty(propertyName);
  }

  public String getValue() {
    String specifiedValue = getSpecifiedValue();
    return specifiedValue == null ? getDefaultValue() : specifiedValue;
  }

  public String getPropertyName() {
    return propertyName;
  }

  public void setProperty(String value) {
    System.setProperty(propertyName, value);
  }

  public void clearProperty() {
    System.clearProperty(propertyName);
  }

  /**
   * Returns the value of either the recommended or the deprecated property if the former wasn't specified.
   * Falls back to the default value of the new property if neither was specified.
   *
   * @param recommended the recommended property
   * @param deprecated  the deprecated property
   * @return the value of either the recommended, or the deprecated property. null if neither was specified and the default
   * value of the new property is null.
   */
  public static String getEitherOf(AngelaProperties recommended, AngelaProperties deprecated) {
    String value = recommended.getSpecifiedValue();
    if (value == null) {
      value = deprecated.getSpecifiedValue();
      if (value != null) {
        logger.warn("Deprecated property '{}' specified. Use '{}' instead to be future-ready", deprecated.propertyName, recommended.propertyName);
      }
    }

    if (value == null) {
      value = recommended.getDefaultValue();
    }
    return value;
  }

  public boolean getBooleanValue() {
    return Boolean.parseBoolean(getValue());
  }
}
