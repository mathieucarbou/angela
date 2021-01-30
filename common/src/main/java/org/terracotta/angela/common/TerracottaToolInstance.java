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

import java.util.Map;
import java.util.function.BiFunction;

public class TerracottaToolInstance {
  private final BiFunction<Map<String, String>, String[], ToolExecutionResult> operation;

  public TerracottaToolInstance(BiFunction<Map<String, String>, String[], ToolExecutionResult> operation) {
    this.operation = operation;
  }

  public ToolExecutionResult execute(Map<String, String> env, String... command) {
    return operation.apply(env, command);
  }
}
