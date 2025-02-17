/*
 * Copyright (C) 2023 Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.slack.sgp.intellij

import com.intellij.DynamicBundle
import java.util.function.Supplier
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE_NAME = "messages.skateBundle"

object SkateBundle : DynamicBundle(BUNDLE_NAME) {
  @Nls
  fun message(
    @PropertyKey(resourceBundle = BUNDLE_NAME) key: String,
    vararg params: Any,
  ): String = getMessage(key, *params)

  @Nls
  fun lazy(
    @PropertyKey(resourceBundle = BUNDLE_NAME) key: String,
    vararg params: Any,
  ): Supplier<String> = getLazyMessage(key, *params)
}
