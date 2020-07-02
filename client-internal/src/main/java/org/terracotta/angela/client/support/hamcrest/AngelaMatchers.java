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
package org.terracotta.angela.client.support.hamcrest;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.terracotta.angela.client.support.junit.NodeOutputRule;
import org.terracotta.angela.common.ToolExecutionResult;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Mathieu Carbou
 */
public class AngelaMatchers {

  public static Matcher<NodeOutputRule.NodeLog> containsLog(String text) {
    return new CustomTypeSafeMatcher<NodeOutputRule.NodeLog>("contains " + text) {
      @Override
      protected boolean matchesSafely(NodeOutputRule.NodeLog result) {
        return result.streamLogsDescending().anyMatch(line -> line.contains(text));
      }
    };
  }

  public static Matcher<ToolExecutionResult> successful() {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("successful") {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return result.getExitStatus() == 0 && find(result.getOutput(), "successful");
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsOutput(String text) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains " + text) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return find(result.getOutput(), text);
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsLinesInOrderStartingWith(Collection<String> expectedLines) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains lines in order starting with:\n" + String.join("\n", expectedLines)) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (out.startsWith(lines.getFirst())) {
            lines.removeFirst();
          }
        }
        return lines.isEmpty();
      }

      @Override
      protected void describeMismatchSafely(ToolExecutionResult result, Description mismatchDescription) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (out.startsWith(lines.getFirst())) {
            lines.removeFirst();
          }
        }
        mismatchDescription.appendText("these lines were not found:\n" + String.join("\n", lines) + "\n in output:\n" + String.join("\n", result.getOutput()));
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsLinesStartingWith(Collection<String> expectedLines) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains lines starting with:\n" + String.join("\n", expectedLines)) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (lines.isEmpty()) {
            break;
          } else {
            lines.removeIf(out::startsWith);
          }
        }
        return lines.isEmpty();
      }

      @Override
      protected void describeMismatchSafely(ToolExecutionResult result, Description mismatchDescription) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (lines.isEmpty()) {
            break;
          } else {
            lines.removeIf(out::startsWith);
          }
        }
        mismatchDescription.appendText("these lines were not found:\n" + String.join("\n", lines) + "\n in output:\n" + String.join("\n", result.getOutput()));
      }
    };
  }

  public static Matcher<ToolExecutionResult> hasExitStatus(int exitStatus) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>(" exist status " + exitStatus) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return result.getExitStatus() == exitStatus;
      }
    };
  }

  private static boolean find(List<String> lines, String text) {
    // reverse search because chances are that the string we are searching for is more at the end than at the beginning
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).contains(text)) {
        return true;
      }
    }
    return false;
  }
}
