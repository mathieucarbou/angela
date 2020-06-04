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

package org.terracotta.angela.common.tcconfig;

import org.terracotta.angela.common.util.HostPort;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Logical definition of a Terracotta server instance
 *
 * @author Aurelien Broszniowski
 */
public class TerracottaServer {

  private final ServerSymbolicName serverSymbolicName;
  private final String hostName;
  private final UUID id;

  private volatile int tsaPort;
  private volatile int tsaGroupPort;
  private volatile int managementPort;
  private volatile int jmxPort;
  private volatile int proxyPort;
  private volatile String bindAddress;
  private volatile String groupBindAddress;
  private volatile String configRepo;
  private volatile String configFile;
  private volatile String logs;
  private volatile String metaData;
  private final List<String> dataDir = new ArrayList<>();
  private final List<String> offheap = new ArrayList<>();
  private volatile String failoverPriority;
  private volatile String clientLeaseDuration;
  private String properties;
  private String backupDir;
  private String clientReconnectWindow;
  private String auditLogDir;
  private Path securityDir;
  private String authc;
  private boolean sslTls;
  private boolean whitelist;

  private TerracottaServer(String serverSymbolicName, String hostName) {
    this.serverSymbolicName = new ServerSymbolicName(serverSymbolicName);
    this.hostName = requireNonNull(hostName);
    this.id = UUID.randomUUID();
  }

  public static TerracottaServer server(String symbolicName, String hostName) {
    return new TerracottaServer(symbolicName, hostName);
  }

  public TerracottaServer tsaPort(int tsaPort) {
    this.tsaPort = tsaPort;
    return this;
  }

  public TerracottaServer tsaGroupPort(int tsaGroupPort) {
    this.tsaGroupPort = tsaGroupPort;
    return this;
  }

  public TerracottaServer managementPort(int managementPort) {
    this.managementPort = managementPort;
    return this;
  }

  public TerracottaServer jmxPort(int jmxPort) {
    this.jmxPort = jmxPort;
    return this;
  }

  public TerracottaServer proxyPort(int proxyPort) {
    this.proxyPort = proxyPort;
    return this;
  }

  public TerracottaServer bindAddress(String bindAddress) {
    this.bindAddress = bindAddress;
    return this;
  }

  public TerracottaServer groupBindAddress(String groupBindAddress) {
    this.groupBindAddress = groupBindAddress;
    return this;
  }

  public TerracottaServer configRepo(String configRepo) {
    this.configRepo = configRepo;
    return this;
  }

  public TerracottaServer configFile(String configFile) {
    this.configFile = configFile;
    return this;
  }

  public TerracottaServer logs(String logs) {
    this.logs = logs;
    return this;
  }

  public TerracottaServer metaData(String metaData) {
    this.metaData = metaData;
    return this;
  }

  public TerracottaServer dataDir(String dataDir) {
    this.dataDir.add(dataDir);
    return this;
  }

  public TerracottaServer backupDir(String backupDir) {
    this.backupDir = backupDir;
    return this;
  }

  public TerracottaServer offheap(String offheap) {
    this.offheap.add(offheap);
    return this;
  }

  public TerracottaServer failoverPriority(String failoverPriority) {
    this.failoverPriority = failoverPriority;
    return this;
  }

  public TerracottaServer clientLeaseDuration(String clientLeaseDuration) {
    this.clientLeaseDuration = clientLeaseDuration;
    return this;
  }

  public TerracottaServer clientReconnectWindow(String clientReconnectWindow) {
    this.clientReconnectWindow = clientReconnectWindow;
    return this;
  }

  public TerracottaServer tcProperties(String properties) {
    this.properties = properties;
    return this;
  }

  public TerracottaServer auditLogDir(String auditLogDir) {
    this.auditLogDir = auditLogDir;
    return this;
  }

  public TerracottaServer securityDir(Path securityDir) {
    this.securityDir = securityDir;
    return this;
  }

  public TerracottaServer authc(String authc) {
    this.authc = authc;
    return this;
  }

  public TerracottaServer sslTls(boolean sslTls) {
    this.sslTls = sslTls;
    return this;
  }

  public TerracottaServer whitelist(boolean whitelist) {
    this.whitelist = whitelist;
    return this;
  }

  public ServerSymbolicName getServerSymbolicName() {
    return serverSymbolicName;
  }

  public UUID getId() {
    return id;
  }

  public String getHostname() {
    return hostName;
  }

  public int getTsaPort() {
    return tsaPort;
  }

  public String getHostPort() {
    return new HostPort(hostName, tsaPort).getHostPort();
  }

  public int getTsaGroupPort() {
    return tsaGroupPort;
  }

  public int getManagementPort() {
    return managementPort;
  }

  public int getJmxPort() {
    return jmxPort;
  }

  public int getProxyPort() {
    return proxyPort;
  }

  public String getBindAddress() {
    return bindAddress;
  }

  public String getGroupBindAddress() {
    return groupBindAddress;
  }

  public String getConfigRepo() {
    return configRepo;
  }

  public String getConfigFile() {
    return configFile;
  }

  public String getMetaData() {
    return metaData;
  }

  public List<String> getDataDir() {
    return dataDir;
  }

  public List<String> getOffheap() {
    return offheap;
  }

  public String getLogs() {
    return logs;
  }

  public String getFailoverPriority() {
    return failoverPriority;
  }

  public String getClientLeaseDuration() {
    return clientLeaseDuration;
  }

  public String getHostName() {
    return hostName;
  }

  public String getProperties() {
    return properties;
  }

  public String getBackupDir() {
    return backupDir;
  }

  public String getClientReconnectWindow() {
    return clientReconnectWindow;
  }

  public String getAuditLogDir() {
    return auditLogDir;
  }

  public Path getSecurityDir() {
    return securityDir;
  }

  public String getAuthc() {
    return authc;
  }

  public boolean isSslTls() {
    return sslTls;
  }

  public boolean isWhitelist() {
    return whitelist;
  }

  @Override
  public String toString() {
    return "TerracottaServer{" +
        "serverSymbolicName=" + serverSymbolicName +
        ", hostName='" + hostName + '\'' +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TerracottaServer that = (TerracottaServer) o;
    return tsaPort == that.tsaPort &&
        tsaGroupPort == that.tsaGroupPort &&
        managementPort == that.managementPort &&
        jmxPort == that.jmxPort &&
        proxyPort == that.proxyPort &&
        sslTls == that.sslTls &&
        whitelist == that.whitelist &&
        serverSymbolicName.equals(that.serverSymbolicName) &&
        hostName.equals(that.hostName) &&
        Objects.equals(id, that.id) &&
        Objects.equals(bindAddress, that.bindAddress) &&
        Objects.equals(groupBindAddress, that.groupBindAddress) &&
        Objects.equals(configRepo, that.configRepo) &&
        Objects.equals(configFile, that.configFile) &&
        Objects.equals(logs, that.logs) &&
        Objects.equals(metaData, that.metaData) &&
        Objects.equals(dataDir, that.dataDir) &&
        Objects.equals(offheap, that.offheap) &&
        Objects.equals(failoverPriority, that.failoverPriority) &&
        Objects.equals(clientLeaseDuration, that.clientLeaseDuration) &&
        Objects.equals(properties, that.properties) &&
        Objects.equals(backupDir, that.backupDir) &&
        Objects.equals(clientReconnectWindow, that.clientReconnectWindow) &&
        Objects.equals(auditLogDir, that.auditLogDir) &&
        Objects.equals(securityDir, that.securityDir) &&
        Objects.equals(authc, that.authc);
  }

  @Override
  public int hashCode() {
    return Objects.hash(serverSymbolicName, hostName, id, tsaPort, tsaGroupPort, managementPort, jmxPort, proxyPort,
        bindAddress, groupBindAddress, configRepo, configFile, logs, metaData, dataDir, offheap, failoverPriority,
        clientLeaseDuration, properties, backupDir, clientReconnectWindow, auditLogDir, securityDir, authc, sslTls, whitelist);
  }
}
