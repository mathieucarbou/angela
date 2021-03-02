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

package org.terracotta.angela.common.topology;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public class Version implements Comparable<Version> {
  private final int major;
  private final int minor;
  private final int revision;
  private final int build_major;
  private final int build_minor;
  private final boolean snapshot;

  public static Version version(String version) {
    return new Version(version);
  }

  Version(String version) {
    requireNonNull(version);

    String versionToSplit = version;
    if (version.endsWith("-SNAPSHOT")) {
      this.snapshot = true;
      versionToSplit = versionToSplit.split("-")[0];
    } else {
      this.snapshot = false;
    }

    String[] split = versionToSplit.split("\\.");
    if (split.length == 2 || split.length == 3 || split.length == 5) {
      this.major = parse(split[0]);
      this.minor = parseMinorVersion(split[1]);
      if (split.length == 2) {
        this.revision = -1;
      } else {
        this.revision = parseRevisionVersion(split[2]);
      }

      if (split.length == 5) {
        this.build_major = parse(split[3]);
        this.build_minor = parse(split[4]);
      } else {
        this.build_major = -1;
        this.build_minor = -1;
      }
    } else {
      throw new IllegalArgumentException("Cannot parse string: " + version + " into a valid Version object");
    }
  }

  private int parseMinorVersion(String input) {
    int minorVersion = parse(input);
    if (minorVersion < 0) {
      throw new IllegalArgumentException("Expected minor version to be a positive number, but found: " + input);
    }
    return minorVersion;
  }

  private int parseRevisionVersion(String input) {
    if (input.contains("pre")) {
      final String[] split = input.split("-");
      input = split[0];
    }
    int revision = parse(input);
    if (revision < 0) {
      throw new IllegalArgumentException("Expected revision to be a positive number, but found: " + input);
    }
    return revision;
  }

  private int parse(String input) {
    int parsed = -1;
    try {
      parsed = Integer.parseInt(input);
    } catch (NumberFormatException e) {
      // Ignore, handled in the next step
    }
    return parsed;
  }

  public int getMajor() {
    return major;
  }

  public int getMinor() {
    return minor;
  }

  public int getRevision() {
    return revision;
  }

  public int getBuild_major() {
    return build_major;
  }

  public int getBuild_minor() {
    return build_minor;
  }

  public boolean isSnapshot() {
    return snapshot;
  }

  // return the version limited to 3 digits, e.g. 4.3.7 instead of 4.3.7.1.2
  public String getShortVersion() {
    StringBuilder sb = new StringBuilder();
    if (major != -1) {
      sb.append(getMajor());
      if (minor != -1) {
        sb.append(".").append(getMinor());
        if (revision != -1) {
          sb.append(".").append(getRevision());
        }
      }
    }
    return sb.toString();
  }


  public String getVersion(boolean showSnapshot) {
    StringBuilder sb = new StringBuilder();
    if (major != -1) {
      sb.append(getMajor());
      if (minor != -1) {
        sb.append(".").append(getMinor());
        if (revision != -1) {
          sb.append(".").append(getRevision());
          if (build_major != -1) {
            sb.append(".").append(getBuild_major());
            if (build_minor != -1) {
              sb.append(".").append(getBuild_minor());
            }
          }
        }
      }
      if (isSnapshot() && showSnapshot) {
        sb.append("-SNAPSHOT");
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return getVersion(true);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Version version = (Version) o;
    return major == version.major &&
        minor == version.minor &&
        revision == version.revision &&
        build_major == version.build_major &&
        build_minor == version.build_minor &&
        snapshot == version.snapshot;
  }

  @Override
  public int hashCode() {
    return Objects.hash(major, minor, revision, build_major, build_minor, snapshot);
  }

  @Override
  public int compareTo(Version ver) {
    if (major > ver.major) {
      return 1;
    } else if (major < ver.major) {
      return -1;
    }

    if (minor > ver.minor) {
      return 1;
    } else if (minor < ver.minor) {
      return -1;
    }

    if (revision > ver.revision) {
      return 1;
    } else if (revision < ver.revision) {
      return -1;
    }

    if (build_major > ver.build_major) {
      return 1;
    } else if (build_major < ver.build_major) {
      return -1;
    }

    if (build_minor > ver.build_minor) {
      return 1;
    } else if (build_minor < ver.build_minor) {
      return -1;
    }

    if (snapshot) {
      return ver.snapshot ? 0 : -1;
    } else {
      return ver.snapshot ? 1 : 0;
    }
  }
}
