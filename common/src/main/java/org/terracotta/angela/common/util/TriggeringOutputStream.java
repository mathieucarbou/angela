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

package org.terracotta.angela.common.util;

import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TriggeringOutputStream extends LogOutputStream {

  private final Consumer<String> consumer;

  public static final TriggeringOutputStream triggerOn(Pattern pattern, Consumer<MatchResult> action) {
    return new TriggeringOutputStream(line -> {
      Matcher matcher = pattern.matcher(line);
      if (matcher.matches()) {
        action.accept(matcher.toMatchResult());
      }
    });
  }

  public final TriggeringOutputStream andTriggerOn(Pattern pattern, Consumer<MatchResult> action) {
    return new TriggeringOutputStream(
        line -> {
          try {
            consumer.accept(line);
          } finally {
            Matcher matcher = pattern.matcher(line);
            if (matcher
                .matches()) {
              action.accept(matcher.toMatchResult());
            }
          }
        });
  }

  public final TriggeringOutputStream andForward(Consumer<String> action) {
    return new TriggeringOutputStream(
        line -> {
          try {
            consumer.accept(line);
          } finally {
            action.accept(line);
          }
        });
  }

  private TriggeringOutputStream(Consumer<String> consumer) {
    this.consumer = consumer;
  }

  @Override
  protected void processLine(final String line) {
    consumer.accept(line);
  }
}
