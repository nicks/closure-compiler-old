Closure Node Package Checker
============================

Closure-NPC is a type-checker for NodeJS programs.

Basic Usage
-----------

Create a `package.json` file for your NodeJS program, 
and add `closure-npc` to your `devDependencies`.

```json
{
  "name": "my-package",
  "main": "./my-package.js",
  ...
  "devDependencies": {
    "closure-npc": "*"
  }
}
```

Then run

```shell
npm install .
./node_modules/.bin/closure-npc .
```

For simple programs, this should work out-of-the-box.  Closure NPC will look up
the "main" script in your package.json, then follow any `require('foo')` calls
to find all your dependencies. Once it has found all the scripts, it will
type-check all of them, assuming that you are using Closure Compiler type
annotations and conventions.

Closure NPC just extends Closure Compiler's type-checking to NodeJS modules. All
the same caveats apply. In particular, there are many ways to write code that
the compiler won't be able to check perfectly, but we believe there's value in
a "best-effort" type checker.

For more on Closure Compiler's type checking, see 
https://developers.google.com/closure/compiler/docs/js-for-compiler.

Advanced Usage
--------------

### Type-checking without package.json

To type-check arbitrary files, run

```shell
npm install closure-npc -g
closure-npc path/to/file1.js path/to/file2.js path/to/file3.js
```

### Type substitutions

By default, closure-npc uses the same module loader algorithm as NodeJS.
For example, if you have

```js
var aws = require('aws-sdk')
```

it will find the aws-sdk package in node_modules, and pull that entire
package into your type-checking job. That library may be large and take
a long time to type-check. It might not have good type annotations, which
defeats the point of pulling it in.

If you create an `externDependencies` in your package.json file, you can
provide alternative type definitions.


```json
{
  "name": "my-package",
  ...
  "externDependencies": {
    "aws-sdk": "./externs/aws-sdk.js"
  }
}
```

Closure NPC will read that file for the type definitions instead of the
normal module.

Known Issues
------------

### Variable Names in Error Messages

When Closure NPC prints an error message, it will use the variable name
from its internal symbol table, not the variable name in the program.

For example, if you have

```js
/** @type {number} */
var x = true;
```

Closure NPC will print an error message like `Type mismatch on assignment to
x$module$main`, which means the variable `x` in `main.js`.

We are working on a better solution for this.

### Error Message Filtering

Closure NPC will print error messages for all files that it finds. 
You can filter errors by type with the normal Closure Compiler warnings
API (https://code.google.com/p/closure-compiler/wiki/Warnings), but you
cannot filter by file path. We may add a flag for this in a future version.

### Dynamic require calls

Closure NPC can only recognize `require` calls on string literals.

```js
require('util') // OK
require('u' + 'til') // NO!
var r = require
r('util') // NO!
```

If it sees anything more complicated than `require("string")`, it will give up.

We're not making any promises one way or the other about how this will
be handled in the future. We may explicitly forbid it with a compiler error, or
there may be a "blessed" way to hide module loads from the compiler.


Contributing
------------

Closure NPC is a thin command-line wrapper around Closure Compiler,
with some additional plugins for processing NodeJS plugins.

Bug reports and pull requests welcome at https://github.com/nicks/closure-compiler/.

If your bug report is not specific to NodeJS, I may redirect you to the
main Closure Compiler project at http://code.google.com/p/closure-compiler/.


Author
------

[Nick Santos](https://github.com/nicks)
supported by
[A Medium Corporation](https://medium.com/).


License
-------

Copyright 2014 The Closure Compiler Authors.

Licensed under the Apache License, Version 2.0.
See http://www.apache.org/licenses/LICENSE-2.0.

See the top-level
[README](https://github.com/nicks/closure-compiler/blob/master/README) for more
complete licensing information.

