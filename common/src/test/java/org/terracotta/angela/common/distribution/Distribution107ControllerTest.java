package org.terracotta.angela.common.distribution;

import org.junit.Test;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.util.OS;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Aurelien Broszniowski
 */

public class Distribution107ControllerTest {

  @Test
  public void testCreateSimpleTsaCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final TerracottaServer terracottaServer = mock(TerracottaServer.class);
    final ServerSymbolicName symbolicName = mock(ServerSymbolicName.class);
    when(terracottaServer.getServerSymbolicName()).thenReturn(symbolicName);
    when(terracottaServer.getHostname()).thenReturn("localhost");
    when(symbolicName.getSymbolicName()).thenReturn("Server1");
    final File kitLocation = new File("/somedir");
    final List<String> args = new ArrayList<>();
    final List<String> tsaCommand = controller.createTsaCommand(terracottaServer, kitLocation, args);

    assertThat(tsaCommand.get(0), is(equalTo(new File("/somedir/server/bin/start-tc-server").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(tsaCommand.get(1), is(equalTo("-n")));
    assertThat(tsaCommand.get(2), is(equalTo("Server1")));
    assertThat(tsaCommand.get(3), is(equalTo("-s")));
    assertThat(tsaCommand.get(4), is(equalTo("localhost")));
    assertThat(tsaCommand.size(), is(5));
  }

  @Test
  public void testCreateSimpleTsaCommandForSAG() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.SAG_INSTALLER);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final TerracottaServer terracottaServer = mock(TerracottaServer.class);
    final ServerSymbolicName symbolicName = mock(ServerSymbolicName.class);
    when(terracottaServer.getServerSymbolicName()).thenReturn(symbolicName);
    when(terracottaServer.getHostname()).thenReturn("localhost");
    when(symbolicName.getSymbolicName()).thenReturn("Server1");
    final File kitLocation = new File("/somedir");
    final List<String> args = new ArrayList<>();
    final List<String> tsaCommand = controller.createTsaCommand(terracottaServer, kitLocation, args);

    assertThat(tsaCommand.get(0), is(equalTo(new File("/somedir/TerracottaDB/server/bin/start-tc-server").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(tsaCommand.get(1), is(equalTo("-n")));
    assertThat(tsaCommand.get(2), is(equalTo("Server1")));
    assertThat(tsaCommand.get(3), is(equalTo("-s")));
    assertThat(tsaCommand.get(4), is(equalTo("localhost")));
    assertThat(tsaCommand.size(), is(5));
  }

  @Test
  public void testCreateSimpleConfigToolCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> configToolCommand = controller.createConfigToolCommand(installLocation, null, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/tools/bin/config-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.size(), is(1));
  }

  @Test
  public void testCreateConfigToolCommandWithArgumentsForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final String[] arguments = {
        "set", "-s", "localhost:9410",
        "-c", "stripe.1.public-hostname=localhost", "-c", "stripe.1.public-port=9411"
    };
    final List<String> configToolCommand = controller.createConfigToolCommand(installLocation, null, arguments);
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/tools/bin/config-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    for (int i = 0; i < arguments.length; i++) {
      assertThat(configToolCommand.get(i + 1), is(arguments[i]));
    }
    assertThat(configToolCommand.size(), is(8));
  }

  @Test
  public void testCreateConfigToolWithSecurityCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final Path securityDir = Paths.get("/securedir");
    final List<String> configToolCommand = controller.createConfigToolCommand(installLocation, securityDir, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/tools/bin/config-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.get(1), is(equalTo("-srd")));
    assertThat(configToolCommand.get(2), is(equalTo(securityDir.toString())));
    assertThat(configToolCommand.size(), is(3));
  }

  @Test
  public void testCreateSimpleConfigToolCommandForSAG() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.SAG_INSTALLER);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> configToolCommand = controller.createConfigToolCommand(installLocation, null, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/TerracottaDB/tools/bin/config-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.size(), is(1));
  }

  @Test
  public void testCreateSimpleClusterToolCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> configToolCommand = controller.createClusterToolCommand(installLocation, null, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/tools/bin/cluster-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.size(), is(1));
  }

  @Test
  public void testCreateClusterToolWithSecurityCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final Path securityDir = Paths.get("/securedir");
    final List<String> configToolCommand = controller.createClusterToolCommand(installLocation, securityDir, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/tools/bin/cluster-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.get(1), is(equalTo("-srd")));
    assertThat(configToolCommand.get(2), is(equalTo(securityDir.toString())));
    assertThat(configToolCommand.size(), is(3));
  }

  @Test
  public void testCreateSimpleClusterToolCommandForSAG() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.SAG_INSTALLER);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> configToolCommand = controller.createClusterToolCommand(installLocation, null, new String[] {});
    assertThat(configToolCommand.get(0), is(equalTo(new File("/somedir/TerracottaDB/tools/bin/cluster-tool").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(configToolCommand.size(), is(1));
  }

  @Test
  public void testStartTmsCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> tsaCommand = controller.startTmsCommand( installLocation);

    assertThat(tsaCommand.get(0), is(equalTo(new File("/somedir/tools/management/bin/start").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(tsaCommand.size(), is(1));
  }

  @Test
  public void testStartTmsCommandForSAG() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.SAG_INSTALLER);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final List<String> tsaCommand = controller.startTmsCommand( installLocation);

    assertThat(tsaCommand.get(0), is(equalTo(new File("/somedir/TerracottaDB/tools/management/bin/start").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(tsaCommand.size(), is(1));
  }

  @Test
  public void testStartVoterCommandForKit() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.KIT);
    when(distribution.getLicenseType()).thenReturn(LicenseType.TERRACOTTA);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final File workDir= new File("/workdir");
    final TerracottaVoter terracottaVoter = mock(TerracottaVoter.class);
    when(terracottaVoter.getHostPorts()).thenReturn(Arrays.asList("9410", "9510"));
    final List<String> voterCommand = controller.startVoterCommand(installLocation, workDir, null, terracottaVoter);

    assertThat(voterCommand.get(0), is(equalTo(new File("/somedir/tools/voter/bin/start-tc-voter").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(voterCommand.get(1), is("-s"));
    assertThat(voterCommand.get(2), is("9410,9510"));
    assertThat(voterCommand.size(), is(3));
  }

  @Test
  public void testStartVoterCommandForSAG() {
    Distribution distribution = mock(Distribution.class);
    when(distribution.getPackageType()).thenReturn(PackageType.SAG_INSTALLER);
    when(distribution.getLicenseType()).thenReturn(LicenseType.TERRACOTTA);
    Distribution107Controller controller = new Distribution107Controller(distribution);

    final File installLocation = new File("/somedir");
    final File workDir = new File("/workDir");
    final TerracottaVoter terracottaVoter = mock(TerracottaVoter.class);
    when(terracottaVoter.getHostPorts()).thenReturn(Arrays.asList("9410", "9510"));
    final List<String> voterCommand = controller.startVoterCommand(installLocation, workDir, null, terracottaVoter);

    assertThat(voterCommand.get(0), is(equalTo(new File("/somedir/TerracottaDB/tools/voter/bin/start-tc-voter").getAbsolutePath() + OS.INSTANCE.getShellExtension())));
    assertThat(voterCommand.get(1), is("-s"));
    assertThat(voterCommand.get(2), is("9410,9510"));
    assertThat(voterCommand.size(), is(3));
  }
}
