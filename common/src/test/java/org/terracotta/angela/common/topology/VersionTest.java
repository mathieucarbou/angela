package org.terracotta.angela.common.topology;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author Aurelien Broszniowski
 */

public class VersionTest {

  @Test
  public void test5digitsVersion() {
    final Version version = new Version("1.7.0.2.124");
    assertThat(version.getMajor(), equalTo(1));
    assertThat(version.getMinor(), equalTo(7));
    assertThat(version.getRevision(), equalTo(0));
    assertThat(version.getBuild_major(), equalTo(2));
    assertThat(version.getBuild_minor(), equalTo(124));
    assertThat(version.getShortVersion(), equalTo("1.7.0"));
  }

  @Test
  public void testpreRelease() {
    final Version version = new Version("1.5.0-pre11");
    assertThat(version.getMajor(), equalTo(1));
    assertThat(version.getMinor(), equalTo(5));
    assertThat(version.getRevision(), equalTo(0));
    assertThat(version.getShortVersion(), equalTo("1.5.0"));
    assertThat(version.isSnapshot(), equalTo(false));

  }

}
