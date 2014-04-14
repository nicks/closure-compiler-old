/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.FieldSetter;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;
import org.kohsuke.args4j.spi.StringOptionHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A CommandLine interface that reads a package.json file and runs
 * the type-checker against the code.
 *
 * @author nicholas.j.santos@gmail.com (Nick Santos)
 */
public class NpmCommandLineRunner extends
    AbstractCommandLineRunner<Compiler, CompilerOptions> {

  final static String NODEJS_LIB_PREFIX = "nodejs.zip//";

  // I don't really care about unchecked warnings in this class.
  @SuppressWarnings("unchecked")
  private static class Flags extends CommandLineRunner.AbstractFlags {
    @Option(name = "--help",
        handler = BooleanOptionHandler.class,
        usage = "Displays this message")
    private boolean displayHelp = false;

    // Used to define the flag, values are stored by the handler.
    @SuppressWarnings("unused")
    @Option(name = "--jscomp_error",
        handler = WarningGuardErrorOptionHandler.class,
        usage = "Make the named class of warnings an error. Options:" +
        DiagnosticGroups.DIAGNOSTIC_GROUP_NAMES)
    private List<String> jscompError = Lists.newArrayList();

    // Used to define the flag, values are stored by the handler.
    @SuppressWarnings("unused")
    @Option(name = "--jscomp_warning",
        handler = WarningGuardWarningOptionHandler.class,
        usage = "Make the named class of warnings a normal warning. " +
        "Options:" + DiagnosticGroups.DIAGNOSTIC_GROUP_NAMES)
    private List<String> jscompWarning = Lists.newArrayList();

    // Used to define the flag, values are stored by the handler.
    @SuppressWarnings("unused")
    @Option(name = "--jscomp_off",
        handler = WarningGuardOffOptionHandler.class,
        usage = "Turn off the named class of warnings. Options:" +
        DiagnosticGroups.DIAGNOSTIC_GROUP_NAMES)
    private List<String> jscompOff = Lists.newArrayList();

    @Option(name = "--charset",
        usage = "Input and output charset for all files. By default, we " +
                "assume UTF-8")
    private String charset = "UTF-8";

    @Option(name = "--extra_annotation_name",
        usage = "A whitelist of tag names in JSDoc. You may specify multiple")
    private List<String> extraAnnotationName = Lists.newArrayList();

    @Option(name = "--version",
        handler = BooleanOptionHandler.class,
        usage = "Prints the compiler version to stderr.")
    private boolean version = false;

    @Argument(
        usage =
        "A directory containing a package.json file with a 'main' script," +
        " or a JS file. May specify multiple." +
        "If none specified, uses the package.json in the current directory")
    private List<String> arguments = Lists.newArrayList();
  }

  private final Flags flags = new Flags();

  private boolean isConfigValid = false;
  private final List<String> entryJsFiles = Lists.newArrayList();
  private final Map<String, SourceFile> externsMap;
  private final Map<String, SourceFile> nodejsMap;

  /**
   * Create a new command-line runner. You should only need to call
   * the constructor if you're extending this class. Otherwise, the main
   * method should instantiate it.
   */
  protected NpmCommandLineRunner(String[] args) throws IOException {
    this(args, System.out, System.err);
  }

  protected NpmCommandLineRunner(
      String[] args, PrintStream out, PrintStream err)
      throws IOException {
    super(out, err);
    this.externsMap = CommandLineRunner.getEmbeddedExternsMap();
    this.nodejsMap = CommandLineRunner.getExternsMap(
        CommandLineRunner.getExternsInputStream("nodejs.zip"),
        NODEJS_LIB_PREFIX);
    initConfigFromFlags(args, err);
  }

  private void initConfigFromFlags(String[] args, PrintStream err) {
    List<String> processedArgs = CommandLineRunner.processArgs(args);

    CmdLineParser parser = new CmdLineParser(flags);
    Flags.guardLevels.clear();
    isConfigValid = true;
    try {
      parser.parseArgument(processedArgs.toArray(new String[] {}));
    } catch (CmdLineException e) {
      err.println(e.getMessage());
      isConfigValid = false;
    }

    if (flags.version) {
      err.println(
          "Closure Node Package Checker (http://code.google.com/closure/compiler)\n" +
          "Version: " + Compiler.getReleaseVersion() + "\n" +
          "Built on: " + Compiler.getReleaseDate());
      err.flush();
    }

    if (!isConfigValid || flags.displayHelp) {
      isConfigValid = false;
      parser.printUsage(err);
      return;
    }

    try {
      for (int i = 0; i < getArgumentCount(); i++) {
        entryJsFiles.add(getMainJsFile(i));
      }
    } catch (Exception e) {
      e.printStackTrace(err);
      isConfigValid = false;
      return;
    }

    CodingConvention conv = new ClosureCodingConvention();

    getCommandLineConfig()
        .setCodingConvention(conv)
        .setWarningGuardSpec(Flags.getWarningGuardSpec())
        .setCharset(flags.charset)
        .setAcceptConstKeyword(true)
        .setLanguageIn("ECMASCRIPT5")
        .setSummaryDetailLevel(3);
  }

  @Override
  protected int doRun() throws FlagUsageException, IOException {
    List<SourceFile> externs = createExterns();
    Compiler compiler = createCompiler();
    CompilerOptions options = createOptions();
    setRunOptions(options);
    compiler.initOptions(options);

    File moduleRoot = new File(getArgument(0));
    if (!moduleRoot.isDirectory()) {
      moduleRoot = moduleRoot.getParentFile();
      if (moduleRoot == null) {
        moduleRoot = new File("./");
      }
    }

    FileSystem fs = FileSystems.getDefault();
    NodeJSModuleLoader loader = new NodeJSModuleLoader(
        compiler, fs.getPath(moduleRoot.toString()).toAbsolutePath().normalize().toString());
    ProcessCommonJSModules processor =
        new ProcessCommonJSModules(
            compiler, loader, false /* do not create modules */);

    // Hack around the compiler API that makes it difficult
    // to inject CompilerInputs directly. Inect them into a module
    // first, then inject that module.
    JSModule module = new JSModule(Compiler.SINGLETON_MODULE_NAME);

    for (String mainJsFile : entryJsFiles) {
      // Bootstrap the module loading process with entry points.
      loader.load(loader.getCanonicalPath(new File(mainJsFile)));

      if (compiler.hasHaltingErrors()) {
        return Math.min(compiler.getErrors().length, 0x7f);
      }
    }

    // Sadly, manageClosureDependencies sorts based on the original
    // source (not the transformed source), so will not put
    // files in the right order on its own. It's easy enough
    // to do it ourselves.
    for (CompilerInput input : loader.inputsInTopologicalOrder) {
      module.add(input);
    }

    Result result = compiler.compileModules(
        externs, Lists.newArrayList(module), options);

    // return 0 if no errors, the error count otherwise
    return Math.min(result.errors.length, 0x7f);
  }

  /** Skip outputs. */
  @Override
  void outputSingleBinary() throws IOException {}

  @Override
  protected CompilerOptions createOptions() {
    CompilerOptions options = new CompilerOptions();
    options.setExtraAnnotationNames(flags.extraAnnotationName);
    options.setIdeMode(true);
    WarningLevel.VERBOSE.setOptionsForWarningLevel(options);
    options.closurePass = true;
    options.inferConsts = true;
    return options;
  }

  @Override
  protected Compiler createCompiler() {
    return new Compiler(getErrorPrintStream());
  }

  @Override
  protected List<SourceFile> createExterns()
      throws FlagUsageException, IOException {
    return Lists.newArrayList(
        externsMap.get("es3.js"),
        externsMap.get("es5.js"),
        externsMap.get("es6.js"),
        externsMap.get("v8.js"),
        externsMap.get("nodejs.js"));
  }

  private SourceFile getNativeLibrary(String name) {
    return nodejsMap.get(name);
  }

  /**
   * Gets the argument at i.
   * Defaults to the current working dir at i=0, and null at i greater than 0
   */
  private String getArgument(int i) {
    if (i < flags.arguments.size()) {
      return flags.arguments.get(i);
    }
    return i == 0 ? "./" : null;
  }

  private int getArgumentCount() {
    return Math.max(1, flags.arguments.size());
  }

  JSONObject getPackageJson(File packageJsonFile)
      throws IOException, JSONException {
    if (!packageJsonFile.isFile()) {
      throw new IOException(
          "No package.json file at " + packageJsonFile.getAbsoluteFile());
    }

    return new JSONObject(
        Files.toString(packageJsonFile, Charset.forName(flags.charset)));
  }

  String getMainJsFile(int arg) throws IOException, JSONException {
    String argPath = FileSystems.getDefault().getPath(getArgument(arg))
        .normalize().toString();
    File argFile = new File(argPath).getAbsoluteFile();
    if (!(argFile.isFile() || argFile.isDirectory())) {
      throw new IOException("Nothing at " + argFile);
    }

    if (argFile.isFile()) {
      return argPath;
    }

    JSONObject packageJson = getPackageJson(new File(argFile, "package.json"));

    String main = null;
    try {
      main = packageJson.getString("main");
    } catch (JSONException e) {} // Fall through to the default main file.

    File mainFile = tryMainFile(argFile, main);
    if (mainFile == null) {
      throw new IOException("Could not find main JS entry point at " + argFile);
    }

    return FileSystems.getDefault().getPath(mainFile.toString())
        .normalize().toString();
  }

  /**
   * Simulates the 'main' search algorithm in nodejs
   */
  static File tryMainFile(File dir, String customName) {
    File candidate = null;

    if (customName != null) {
      candidate = new File(dir, customName);
      if (candidate.exists()) return candidate;

      candidate = new File(dir, customName + ".js");
      if (candidate.exists()) return candidate;
    }

    candidate = new File(dir, "index");
    if (candidate.exists()) return candidate;

    candidate = new File(dir, "index.js");
    if (candidate.exists()) return candidate;

    return null;
  }

  /**
   * Simulates the normal js search algorithm in nodejs
   */
  static File tryFile(File dir, String customName) {
    // Do not try to load other types of requires, like json files.
    if (customName.endsWith(".json")) {
      return new File(dir, customName + ".js");
    }

    File candidate = new File(dir, customName);
    if (candidate.exists()) {
      if (candidate.isDirectory()) {
        return tryMainFile(candidate, null);
      }
      return candidate;
    }

    return new File(dir, customName + ".js");
  }

  /**
   * @return Whether the configuration is valid.
   */
  public boolean shouldRunCompiler() {
    return this.isConfigValid;
  }

  /**
   * Runs the Compiler. Exits cleanly in the event of an error.
   */
  public static void main(String[] args) throws IOException {
    NpmCommandLineRunner runner = new NpmCommandLineRunner(args);
    if (runner.shouldRunCompiler()) {
      runner.run();
    } else {
      System.exit(-1);
    }
  }


  /**
   * The NodeJS module loader uses NodeJS lookup semantics.
   *
   * In normal NodeJS operation, the "module root" is always relative to the
   * current file.  require('q') traverses the ancestor tree looking for a
   * node_modules directory, finds node_modules/q/package.json, and uses the
   * main entry point to find the module. It may also look in a global
   * registry.
   *
   * This module loader works similarly, but with one minor tweak.
   * If package.json contains an entry "q" in the map "externsDependencies",
   * we will use the value of that map instead of the normal dependency.
   *
   * To use this module loader, find the entry point of our program,
   * and run ProcessCommonJSModules on it with this module loader.
   * Every time ProcessCommonJSModules loads a file, this loader
   * will dynamically create a new CompilerInput and run it through
   * ProcessCommonJSModules.
   */
  class NodeJSModuleLoader extends ES6ModuleLoader {
    private final Compiler compiler;
    private final Map<String, CompilerInput> inputsByAddress = Maps.newHashMap();
    private final List<CompilerInput> inputsInTopologicalOrder = Lists.newArrayList();
    private final Path moduleRoot;

    NodeJSModuleLoader(Compiler compiler, String moduleRoot) {
      this.compiler = compiler;
      this.moduleRoot = getAbsolutePath(moduleRoot);
    }

    /**
     * NodeJS always resolves symlinks, so use the OS-specific canonical
     * path for module addresses.
     */
    @Override
    String locate(String name, CompilerInput referrer) {
      File currentDir = new File(referrer.getName())
          .getParentFile();
      if (ES6ModuleLoader.isRelativeIdentifier(name)) {
        return getCanonicalPath(tryFile(currentDir, name));
      }

      while (currentDir != null) {
        File candidate = resolveTopLevelModuleAt(currentDir, name);
        if (candidate != null && candidate.isFile()) {
          return getCanonicalPath(candidate);
        }
        currentDir = currentDir.getParentFile();
      }

      if (getNativeLibrary(name + ".js") != null) {
        return NODEJS_LIB_PREFIX + name + ".js";
      }
      return null;
    }

    private File resolveTopLevelModuleAt(File currentDir, String name) {
      File packageFile = new File(currentDir, "package.json");
      if (packageFile.isFile()) {
        try {
          JSONObject packageJson = getPackageJson(packageFile);
          JSONObject externDependencies =
              packageJson.getJSONObject("externDependencies");
          String myExtern = externDependencies.getString(name);
          return new File(currentDir, myExtern);
        }
        catch (JSONException e) {} // no one cares
        catch (IOException e) {} // no one cares
      }

      File nodeModulesFolder = new File(currentDir, "node_modules");
      if (nodeModulesFolder.isDirectory()) {
        File moduleFolder = new File(nodeModulesFolder, name);
        if (moduleFolder.isDirectory()) {
          packageFile = new File(moduleFolder, "package.json");
          if (packageFile.isFile()) {
            String main = null;
            try {
              JSONObject packageJson = getPackageJson(packageFile);
              main = packageJson.getString("main");
            }
            catch (JSONException e) {} // no one cares
            catch (IOException e) {} // no one cares

            return tryMainFile(moduleFolder, main);
          }
        }
      }
      return null;
    }

    @Override
    CompilerInput load(String name) {
      CompilerInput input = inputsByAddress.get(name);
      if (input != null) return input;

      SourceFile newFile = null;
      if (name.startsWith(NODEJS_LIB_PREFIX)) {
        newFile = getNativeLibrary(name.substring(NODEJS_LIB_PREFIX.length()));
      } else {
        String path = moduleRoot.resolve(name).normalize().toString();
        newFile = SourceFile.fromFile(path);
      }

      CompilerInput newInput = new CompilerInput(newFile);
      inputsByAddress.put(name, newInput);
      compiler.putCompilerInput(new InputId(name), newInput);

      Node root = newInput.getAstRoot(compiler);
      if (compiler.hasHaltingErrors()) {
        return newInput;
      }

      ProcessCommonJSModules processor =
          new ProcessCommonJSModules(compiler, this, false);
      processor.process(newInput);
      inputsInTopologicalOrder.add(newInput);
      return newInput;
    }

    @Override
    String getLoadAddress(CompilerInput input) {
      String nativeLibraryPrefix = NODEJS_LIB_PREFIX;
      if (input.getName().startsWith(nativeLibraryPrefix)) {
        return input.getName();
      }

      return getCanonicalPath(new File(input.getName()));
    }

    private Path getAbsolutePath(String fileName) {
      return FileSystems.getDefault().getPath(fileName)
          .toAbsolutePath().normalize();
    }

    private String getCanonicalPath(File file) {
      try {
        return moduleRoot.relativize(
            getAbsolutePath(file.getCanonicalPath()))
            .normalize().toString();
      } catch (IOException e) {
        // Just let any IO exceptions propagate up to the CLI.
        throw new RuntimeException(e);
      }
    }
  }
}
