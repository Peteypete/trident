```clojure
{trident/<artifact> {:mvn/version "0.1.3"}}
```

# Trident

> Because I had to call it something

Trident is my personal experiment in creating a highly-abstracted web
application framework for use with Datomic Cloud. But it's also just a
collection of libraries, containing anything I happen to abstract out of
projects I'm working on.

My top-level goal with Trident is to maximize code reuse (especially my own, but
hopefully for others as well). This has two parts:

 - There should be high-level interfaces that give you lots of leverage (like
   frameworks).

 - When the high-level interfaces don't cut it, it should be easy to use only
   the parts you want, customizing behavior as needed (like libraries).

## Contents

I keep the code in a single `src` dir, but I provide multiple artifacts. Any
code in `src/trident/<foo>` is available in the `tridnet/<foo>` artifact. For
example, `{trident/cli {:mvn/version "<version>"}}` will give you access to the
`trident.cli` namespace. All artifacts use the same version.

I use the `trident/docs` artifact for [documentation on cljdoc]. You can
[browse the namespaces](https://cljdoc.org/d/trident/docs/CURRENT/api/trident)
to see what's available, but here's a list of the artifacts for convenience:

 - [`trident/repl`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.repl). A handful of convenience functions for use at the repl.

 - [`trident/util`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.util). A collection of utility functions & macros.

 - [`trident/cli`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.cli) (includes `util`). Makes it easy to define and reuse CLIs for tools.deps-based build tasks.

 - [`trident/build`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.build) (includes `util`, `cli`). A collection of build tasks made with `trident/cli`.

 - [`trident/datomic`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.datomic) (includes `util`). Janky file-based persistence for Datomic Free.

 - [`trident/datomic-cloud`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.datomic-cloud) (includes `util`). Tools for Datomic Cloud.

 - [`trident/ring`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.ring). Some Ring middleware.

 - [`trident/firebase`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.firebase). Functions for authenticating Firebase user tokens.

 - [`trident/ion`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.ion) (includes `util`). Utilities for working with Datomic Ions

 - [`trident/web`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.web) (includes `util`, `datomic-cloud`, `firebase`, `ion`, `ring`). Highly contrived web framework.

 - [`trident/web-repl`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.web-repl) (includes `web`). A dev web server for use with `trident/web`.

 - [`trident/datascript`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.datascript) (includes `util`). Frontend tools for syncing Datascript with Datomic.

 - [`trident/views`](https://cljdoc.org/d/trident/docs/CURRENT/api/trident.views) (includes `util`). Some Reagent components and stuff.

 - `trident/frontend` (includes `util`, `datascript`, `views`). Just a bundle of other artifacts.

 Also see [trepl.py], a script that makes running build tasks fast.

## Progress

While I hope Trident will be useful for others, I'm primarily focusing on my own
use cases because 1) there are things I want to build, 2) developing Trident as
I build real things will help to make sure it's actually useful. With that out
of the way, if you're interested in anything Trident provides, I'd love to chat.

## Contact

 - [email](mailto:foo@jacobobryant.com)
 - [twitter](https://twitter.com/obryant666)
 - `#trident` on [Clojurians](https://clojurians.slack.com)

## License

Distributed under the [EPL v2.0](LICENSE)

Copyright &copy; 2019 [Jacob O'Bryant](https://jacobobryant.com).

[documentation on cljdoc]: https://cljdoc.org/d/trident/docs/CURRENT/doc/readme
[trepl.py]: https://cljdoc.org/d/trident/docs/CURRENT/doc/running-build-tasks-quickly-with-trepl-py-
