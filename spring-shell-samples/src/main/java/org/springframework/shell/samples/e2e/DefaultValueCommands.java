/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.shell.samples.e2e;

import org.springframework.context.annotation.Bean;
import org.springframework.shell.command.CommandRegistration;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Commands used for e2e test.
 *
 * @author Janne Valkealahti
 */
@ShellComponent
public class DefaultValueCommands extends BaseE2ECommands {

	@ShellMethod(key = LEGACY_ANNO + "default-value", group = GROUP)
	public String testDefaultValue(
		@ShellOption(defaultValue = "hi") String arg1
	) {
		return "Hello " + arg1;
	}

	@Bean
	public CommandRegistration testDefaultValueRegistration() {
		return CommandRegistration.builder()
			.command(REG, "default-value")
			.group(GROUP)
			.withOption()
				.longNames("arg1")
				.defaultValue("hi")
				.and()
			.withTarget()
				.function(ctx -> {
					String arg1 = ctx.getOptionValue("arg1");
					return "Hello " + arg1;
				})
				.and()
			.build();
	}
}
