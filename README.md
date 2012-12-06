## Git Plugin

A fork of the [Jenkins git-plugin](https://github.com/jenkinsci/git-plugin) project created to generate shortstat change sets. I would not advise using this plugin on your Jenkins instiliation as it is built for a very specific purpose and not fit for production.

This plugin provides `git log --short-stat` change set information via the Jenkins API instead of `whatchanged` as is the case with the original plugin.

`git log --short-stat` provides more useful information such as files changed, deletions and insertions.

### Todo

* Fix tests
* Clean up commit parsing
