/*
 * Copyright 2017-2022 the original author or authors.
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
package org.springframework.shell;

import java.lang.reflect.UndeclaredThrowableException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.jline.terminal.Terminal;
import org.jline.utils.Signals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.shell.command.CommandAlias;
import org.springframework.shell.command.CommandCatalog;
import org.springframework.shell.command.CommandExecution;
import org.springframework.shell.command.CommandOption;
import org.springframework.shell.command.CommandExecution.CommandExecutionException;
import org.springframework.shell.command.CommandExecution.CommandExecutionHandlerMethodArgumentResolvers;
import org.springframework.shell.command.CommandRegistration;
import org.springframework.shell.completion.CompletionResolver;
import org.springframework.shell.context.InteractionMode;
import org.springframework.shell.context.ShellContext;
import org.springframework.shell.exit.ExitCodeMappings;
import org.springframework.util.StringUtils;

/**
 * Main class implementing a shell loop.
 *
 * @author Eric Bottard
 * @author Janne Valkealahti
 */
public class Shell {

	private final static Logger log = LoggerFactory.getLogger(Shell.class);
	private final ResultHandlerService resultHandlerService;

	/**
	 * Marker object returned to signify that there was no input to turn into a command
	 * execution.
	 */
	public static final Object NO_INPUT = new Object();

	private final Terminal terminal;
	private final CommandCatalog commandRegistry;
	protected List<CompletionResolver> completionResolvers = new ArrayList<>();
	private CommandExecutionHandlerMethodArgumentResolvers argumentResolvers;
	private ConversionService conversionService = new DefaultConversionService();
	private final ShellContext shellContext;
	private final ExitCodeMappings exitCodeMappings;

	/**
	 * Marker object to distinguish unresolved arguments from {@code null}, which is a valid
	 * value.
	 */
	protected static final Object UNRESOLVED = new Object();

	private Validator validator = Utils.defaultValidator();

	public Shell(ResultHandlerService resultHandlerService, CommandCatalog commandRegistry, Terminal terminal,
			ShellContext shellContext, ExitCodeMappings exitCodeMappings) {
		this.resultHandlerService = resultHandlerService;
		this.commandRegistry = commandRegistry;
		this.terminal = terminal;
		this.shellContext = shellContext;
		this.exitCodeMappings = exitCodeMappings;
	}

	@Autowired
	public void setCompletionResolvers(List<CompletionResolver> resolvers) {
		this.completionResolvers = new ArrayList<>(resolvers);
		AnnotationAwareOrderComparator.sort(completionResolvers);
	}

	@Autowired
	public void setArgumentResolvers(CommandExecutionHandlerMethodArgumentResolvers argumentResolvers) {
		this.argumentResolvers = argumentResolvers;
	}

	public void setConversionService(ConversionService shellConversionService) {
		this.conversionService = shellConversionService;
	}

	@Autowired(required = false)
	public void setValidatorFactory(ValidatorFactory validatorFactory) {
		this.validator = validatorFactory.getValidator();
	}

	/**
	 * The main program loop: acquire input, try to match it to a command and evaluate. Repeat
	 * until a {@link ResultHandler} causes the process to exit or there is no input.
	 * <p>
	 * This method has public visibility so that it can be invoked by actual commands
	 * (<em>e.g.</em> a {@literal script} command).
	 * </p>
	 */
	public void run(InputProvider inputProvider) throws Exception {
		Object result = null;
		while (!(result instanceof ExitRequest)) { // Handles ExitRequest thrown from Quit command
			Input input;
			try {
				input = inputProvider.readInput();
			}
			catch (Exception e) {
				if (e instanceof ExitRequest) { // Handles ExitRequest thrown from hitting CTRL-C
					break;
				}
				resultHandlerService.handle(e);
				continue;
			}
			if (input == null) {
				break;
			}

			result = evaluate(input);
			if (result != NO_INPUT && !(result instanceof ExitRequest)) {
				resultHandlerService.handle(result);
			}

			// throw if not in interactive mode so that boot's exit code feature
			// can contribute exit code. we can't throw when in interactive mode as
			// that would exit a shell
			if (this.shellContext != null && this.shellContext.getInteractionMode() != InteractionMode.INTERACTIVE) {
				if (result instanceof CommandExecution.CommandParserExceptionsException) {
					throw (CommandExecution.CommandParserExceptionsException) result;
				}
				else if (result instanceof Exception) {
					throw (Exception) result;
				}
			}
		}
	}

	/**
	 * Evaluate a single "line" of input from the user by trying to map words to a command and
	 * arguments.
	 *
	 * <p>
	 * This method does not throw exceptions, it catches them and returns them as a regular
	 * result
	 * </p>
	 */
	public Object evaluate(Input input) {
		if (noInput(input)) {
			return NO_INPUT;
		}

		String line = input.words().stream().collect(Collectors.joining(" ")).trim();
		String command = findLongestCommand(line);

		List<String> words = input.words();
		log.debug("Evaluate input with line=[{}], command=[{}]", line, command);
		if (command != null) {

			Optional<CommandRegistration> commandRegistration = commandRegistry.getRegistrations().values().stream()
				.filter(r -> {
					if (r.getCommand().equals(command)) {
						return true;
					}
					for (CommandAlias a : r.getAliases()) {
						if (a.getCommand().equals(command)) {
							return true;
						}
					}
					return false;
				})
				.findFirst();

			if (commandRegistration.isPresent()) {
				if (this.exitCodeMappings != null) {
					List<Function<Throwable, Integer>> mappingFunctions = commandRegistration.get().getExitCode()
							.getMappingFunctions();
					this.exitCodeMappings.reset(mappingFunctions);
				}

				List<String> wordsForArgs = wordsForArguments(command, words);

				Thread commandThread = Thread.currentThread();
				Object sh = Signals.register("INT", () -> commandThread.interrupt());
				try {
					CommandExecution execution = CommandExecution
							.of(argumentResolvers != null ? argumentResolvers.getResolvers() : null, validator, terminal, conversionService);
					return execution.evaluate(commandRegistration.get(), wordsForArgs.toArray(new String[0]));
				}
				catch (UndeclaredThrowableException e) {
					if (e.getCause() instanceof InterruptedException || e.getCause() instanceof ClosedByInterruptException) {
						Thread.interrupted(); // to reset interrupted flag
					}
					return e.getCause();
				}
				catch (CommandExecutionException e) {
					return e.getCause();
				}
				catch (Exception e) {
					return e;
				}
				finally {
					Signals.unregister("INT", sh);
				}
			}
			else {
				return new CommandNotFound(words);
			}
		}
		else {
			return new CommandNotFound(words);
		}
	}


	/**
	 * Return true if the parsed input ends up being empty (<em>e.g.</em> hitting ENTER on an
	 * empty line or blank space).
	 *
	 * <p>
	 * Also returns true (<em>i.e.</em> ask to ignore) when input starts with {@literal //},
	 * which is used for comments.
	 * </p>
	 */
	private boolean noInput(Input input) {
		return input.words().isEmpty()
				|| (input.words().size() == 1 && input.words().get(0).trim().isEmpty())
				|| (input.words().iterator().next().matches("\\s*//.*"));
	}

	/**
	 * Returns the list of words to be considered for argument resolving. Drops the first N
	 * words used for the command, as well as an optional empty word at the end of the list
	 * (which may be present if user added spaces before submitting the buffer)
	 */
	private List<String> wordsForArguments(String command, List<String> words) {
		int wordsUsedForCommandKey = command.split(" ").length;
		List<String> args = words.subList(wordsUsedForCommandKey, words.size());
		int last = args.size() - 1;
		if (last >= 0 && "".equals(args.get(last))) {
			args.remove(last);
		}
		return args;
	}

	/**
	 * Gather completion proposals given some (incomplete) input the user has already typed
	 * in. When and how this method is invoked is implementation specific and decided by the
	 * actual user interface.
	 */
	public List<CompletionProposal> complete(CompletionContext context) {

		String prefix = context.upToCursor();

		List<CompletionProposal> candidates = new ArrayList<>();
		candidates.addAll(commandsStartingWith(prefix));

		String best = findLongestCommand(prefix);
		if (best != null) {
			context = context.drop(best.split(" ").length);
			CommandRegistration registration = commandRegistry.getRegistrations().get(best);
			CompletionContext argsContext = context.commandRegistration(registration);

			for (CompletionResolver resolver : completionResolvers) {
				List<CompletionProposal> resolved = resolver.apply(argsContext);
				candidates.addAll(resolved);
			}

			// Try to complete arguments
			List<CommandOption> matchedArgOptions = new ArrayList<>();
			if (argsContext.getWords().size() > 0) {
				matchedArgOptions.addAll(matchOptions(registration.getOptions(), argsContext.getWords().get(0)));
			}

			List<CompletionProposal> argProposals =	matchedArgOptions.stream()
				.flatMap(o -> {
					Function<CompletionContext, List<CompletionProposal>> completion = o.getCompletion();
					if (completion != null) {
						List<CompletionProposal> apply = completion.apply(argsContext.commandOption(o));
						return apply.stream();
					}
					return Stream.empty();
				})
				.collect(Collectors.toList());

			candidates.addAll(argProposals);
		}
		return candidates;
	}

	private List<CommandOption> matchOptions(List<CommandOption> options, String arg) {
		List<CommandOption> matched = new ArrayList<>();
		String trimmed = StringUtils.trimLeadingCharacter(arg, '-');
		int count = arg.length() - trimmed.length();
		if (count == 1) {
			if (trimmed.length() == 1) {
				Character trimmedChar = trimmed.charAt(0);
				options.stream()
					.filter(o -> {
						for (Character sn : o.getShortNames()) {
							if (trimmedChar.equals(sn)) {
								return true;
							}
						}
						return false;
					})
				.findFirst()
				.ifPresent(o -> matched.add(o));
			}
			else if (trimmed.length() > 1) {
				trimmed.chars().mapToObj(i -> (char)i)
					.forEach(c -> {
						options.stream().forEach(o -> {
							for (Character sn : o.getShortNames()) {
								if (c.equals(sn)) {
									matched.add(o);
								}
							}
						});
					});
			}
		}
		else if (count == 2) {
			options.stream()
				.filter(o -> {
					for (String ln : o.getLongNames()) {
						if (trimmed.equals(ln)) {
							return true;
						}
					}
					return false;
				})
				.findFirst()
				.ifPresent(o -> matched.add(o));
		}
		return matched;
	}

	private List<CompletionProposal> commandsStartingWith(String prefix) {
		// Workaround for https://github.com/spring-projects/spring-shell/issues/150
		// (sadly, this ties this class to JLine somehow)
		int lastWordStart = prefix.lastIndexOf(' ') + 1;
		return commandRegistry.getRegistrations().entrySet().stream()
			.filter(e -> e.getKey().startsWith(prefix))
			.map(e -> {
				String c = e.getKey();
				c = c.substring(lastWordStart);
				return toCommandProposal(c, e.getValue());
			})
			.collect(Collectors.toList());
	}

	private CompletionProposal toCommandProposal(String command, CommandRegistration registration) {
		return new CompletionProposal(command)
				.dontQuote(true)
				.category("Available commands")
				.description(registration.getDescription());
	}

	/**
	 * Returns the longest command that can be matched as first word(s) in the given buffer.
	 *
	 * @return a valid command name, or {@literal null} if none matched
	 */
	private String findLongestCommand(String prefix) {
		String result = commandRegistry.getRegistrations().keySet().stream()
				.filter(command -> prefix.equals(command) || prefix.startsWith(command + " "))
				.reduce("", (c1, c2) -> c1.length() > c2.length() ? c1 : c2);
		return "".equals(result) ? null : result;
	}
}
