/*
 * Copyright 2013 The Closure Compiler Authors.
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
package com.google.javascript.jscomp.fuzzing;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

import java.util.Set;

/**
 * UNDER DEVELOPMENT. DO NOT USE!
 */
class ForInFuzzer extends AbstractFuzzer {
  ForInFuzzer(FuzzingContext context) {
    super(context);
  }

  /* (non-Javadoc)
   * @see com.google.javascript.jscomp.fuzzing.AbstractFuzzer#isEnough(int)
   */
  @Override
  protected boolean isEnough(int budget) {
    return budget >= 4;
  }

  /* (non-Javadoc)
   * @see com.google.javascript.jscomp.fuzzing.AbstractFuzzer#generate(int)
   */
  @Override
  protected Node generate(int budget, Set<Type> types) {
    Scope localScope = context.scopeManager.localScope();
    localScope.loopNesting++;
    AbstractFuzzer[] fuzzers = {
        new ForInItemFuzzer(context),
      new ExpressionFuzzer(context),
      new BlockFuzzer(context)
    };
    Node[] components = distribute(budget - 1, fuzzers);
    localScope.loopNesting--;
    return new Node(Token.FOR, components);
  }

  private static class ForInItemFuzzer extends Dispatcher {

    ForInItemFuzzer(FuzzingContext context) {
      super(context);
    }

    /* (non-Javadoc)
     * @see com.google.javascript.jscomp.fuzzing.Dispatcher#initCandidates()
     */
    @Override
    protected void initCandidates() {
      candidates = new AbstractFuzzer[] {
          new AssignableExprFuzzer(context),
          new VarFuzzer(context)
      };
    }

    /* (non-Javadoc)
     * @see com.google.javascript.jscomp.fuzzing.AbstractFuzzer#getConfigName()
     */
    @Override
    protected String getConfigName() {
      return "forInItem";
    }

  }

  /* (non-Javadoc)
   * @see com.google.javascript.jscomp.fuzzing.AbstractFuzzer#getConfigName()
   */
  @Override
  protected String getConfigName() {
    return "forIn";
  }
}
