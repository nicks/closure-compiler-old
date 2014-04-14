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

import com.google.common.collect.Maps;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Provides compile-time locate semantics for ES6 and CommonJS modules.
 *
 * @see Section 26.3.3.18.2 of the ES6 spec
 * @see http://wiki.commonjs.org/wiki/Modules/1.1
 */
abstract class ES6ModuleLoader {
  /**
   * According to the spec, the forward slash should be the delimiter on all
   * platforms.
   */
  static final String MODULE_SLASH = "/";

  /**
   * Whether this is relative to the current file, or a top-level identifier.
   */
  static boolean isRelativeIdentifier(String name) {
    return name.startsWith("." + MODULE_SLASH) ||
        name.startsWith(".." + MODULE_SLASH);
  }

  /**
   * The normalize hook creates a global qualified name for a module, and then
   * the locate hook creates an address. Meant to mimic the behavior of these
   * two hooks.
   * @param name The name passed to the require() call
   * @param referrer The file where we're calling from
   * @return A globally unique address.
   */
  abstract String locate(String name, CompilerInput referrer);

  /**
   * Locates a compiler input by ES6 module address.
   *
   * If the input doesn't exist, the implementation can decide whether to create
   * an input, or fail softly (by returning null), or throw an error.
   */
  abstract CompilerInput load(String name) throws LoadFailedException;

  /**
   * Gets the ES6 module address for an input.
   */
  abstract String getLoadAddress(CompilerInput input);

  /**
   * Error thrown when a load fails.
   */
  static class LoadFailedException extends Exception {
    final String loadAddress;

    LoadFailedException(String reason, String loadAddress) {
      super(reason);
      this.loadAddress = loadAddress;
    }
  }

  /**
   * A naive module loader treats all module references as direct file paths.
   *
   * require('./foo') refers to 'foo.js' in the current directory.
   * require('foo') refers to 'foo.js' in the directory where the compiler was
   * run.
   *
   * This module loader does not know how to load files. If a file doesn't
   * exist yet, then load() will fail.
   *
   * @param moduleRoot The module root, relative to the compiler's
   *     current working directory.
   */
  static ES6ModuleLoader createNaiveLoader(
      AbstractCompiler compiler, String moduleRoot) {
    return new NaiveModuleLoader(compiler, moduleRoot);
  }

  private static class NaiveModuleLoader extends ES6ModuleLoader {
    private final AbstractCompiler compiler;
    private final Map<String, CompilerInput> inputsByAddress =
        Maps.newHashMap();
    private final String moduleRoot;

    private NaiveModuleLoader(AbstractCompiler compiler, String moduleRoot) {
      this.compiler = compiler;
      this.moduleRoot = moduleRoot;

      // Precompute the module name of each source file.
      for (CompilerInput input : compiler.getInputsInOrder()) {
        inputsByAddress.put(getLoadAddress(input), input);
      }
    }

    @Override
    String locate(String name, CompilerInput referrer) {
      if (isRelativeIdentifier(name)) {
        return convertSourceUriToModuleAddress(
            createUri(referrer.getName()).resolve(createUri(name)));
      }
      return createUri(name).normalize().toString();
    }

    @Override
    CompilerInput load(String name) {
      return inputsByAddress.get(name);
    }

    @Override
    String getLoadAddress(CompilerInput input) {
      return convertSourceUriToModuleAddress(createUri(input.getName()));
    }

    // TODO(nicksantos): Figure out a better way to deal with
    // URI syntax errors.
    private static URI createUri(String uri) {
      try {
        return new URI(uri);
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }

    private String convertSourceUriToModuleAddress(URI uri) {
      String filename = uri.normalize().toString();

      // The DOS command shell will normalize "/" to "\", so we have to
      // wrestle it back to conform the the module standard.
      filename = filename.replace("\\", MODULE_SLASH);

      // TODO(nicksantos): It's not totally clear to me what
      // should happen if a file is not under the given module root.
      // Maybe this should be an error, or resolved differently.
      if (!moduleRoot.isEmpty() &&
          filename.indexOf(moduleRoot) == 0) {
        filename = filename.substring(moduleRoot.length());
      }

      return filename;
    }
  }
}
