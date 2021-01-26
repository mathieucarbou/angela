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

package org.terracotta.angela.common.distribution;

import java.util.Arrays;
import java.util.EnumSet;
import org.terracotta.angela.common.topology.LicenseType;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Version;

import java.util.Objects;

import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA_OS;
import static org.terracotta.angela.common.topology.LicenseType.GO;
import static org.terracotta.angela.common.topology.LicenseType.MAX;
import static org.terracotta.angela.common.topology.LicenseType.TERRACOTTA;
import static java.util.Objects.requireNonNull;

/**
 * @author Aurelien Broszniowski
 */

public class Distribution {

  private final Version version;
  private final PackageType packageType;
  private final LicenseType licenseType;
  private final EnumSet<RuntimeOption> runtimeOptions;

  private Distribution(Version version, PackageType packageType, LicenseType licenseType, EnumSet<RuntimeOption> runtimeOptions) {
    this.version = requireNonNull(version);
    this.packageType = requireNonNull(packageType);
    this.licenseType = validateLicenseType(version, licenseType);
    this.runtimeOptions = runtimeOptions;
  }

  private LicenseType validateLicenseType(Version version, LicenseType licenseType) {
    requireNonNull(licenseType);
    if (version.getMajor() == 4) {
      if (licenseType != GO && licenseType != MAX) {
        return throwException("Expected license of type '%s' or '%s for version: %s, but found: %s", GO, MAX, version, licenseType);
      }
    } else if (version.getMajor() == 3 || version.getMajor() == 5) {
      if (licenseType != TERRACOTTA_OS) {
        throwException("Expected license of type '%s' for version: %s, but found: %s", TERRACOTTA_OS, version, licenseType);
      }
    } else {
      if (licenseType != TERRACOTTA) {
        throwException("Expected license of type '%s' for version: %s, but found: %s", TERRACOTTA, version, licenseType);
      }
    }
    return licenseType;
  }

  private LicenseType throwException(String string, Object... args) {
    throw new IllegalArgumentException(String.format(string, args));
  }

  public static Distribution distribution(Version version, PackageType packageType, LicenseType licenseType) {
    return new Distribution(version, packageType, licenseType, EnumSet.noneOf(RuntimeOption.class));
  }
  
  public static Distribution distribution(Version version, PackageType packageType, LicenseType licenseType, RuntimeOption...runtime) {
    return new Distribution(version, packageType, licenseType, EnumSet.copyOf(Arrays.asList(runtime)));
  }

  public Version getVersion() {
    return version;
  }

  public PackageType getPackageType() {
    return packageType;
  }

  public LicenseType getLicenseType() {
    return licenseType;
  }

  public DistributionController createDistributionController() {
    if ((version.getMajor() == 10 && version.getMinor() >= 7) //tc-ee 10.7
        || (version.getMajor() == 5 && version.getMinor() >= 7) //tc-platform 5.7 and above
        || (version.getMajor() == 3 && version.getMinor() >= 9) //ehcache 3.9 and above
    ) {
      if (runtimeOptions.contains(RuntimeOption.INLINE_SERVERS)) {
        return new Distribution107InlineController(this);
      } else {
        return new Distribution107Controller(this);
      }
    }

    if (version.getMajor() == 10 || version.getMajor() == 3) {
      return new Distribution102Controller(this);
    } else {
      if (version.getMinor() >= 3) {
        return new Distribution43Controller(this);
      }
    }
    throw new IllegalStateException("Cannot create a DistributionController for version: " + version);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Distribution that = (Distribution) o;
    return Objects.equals(version, that.version) &&
        packageType == that.packageType &&
        licenseType == that.licenseType;
  }

  @Override
  public int hashCode() {
    return Objects.hash(version, packageType, licenseType);
  }

  @Override
  public String toString() {
    return "Distribution{" +
           "version=" + version +
           ", packageType=" + packageType +
           ", licenseType=" + licenseType +
           '}';
  }
}
