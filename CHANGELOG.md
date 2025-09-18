# Changelog

## Unreleased (1.2.0-SNAPSHOT)

- Deferred :find / :aggregate binding fix: query patterns now tolerate being visited before the JSON literal
  produced via BIND chains (queryExpected flag) instead of prematurely closing the iterator and yielding no rows.
- Graph redirection ordering: ensured :graph assignment is processed before any model pattern so redirected results
  appear only in the target graph (fixes leakage into the original collection graph).
- Batched execution ordering heuristic: increased penalty for scheduling :entity before an unbound query (base + 20)
  preventing zero-result plans in batched mode when the query literal is produced by BIND. Validated by batched
  BIND tests now returning expected 4 rows.

## 1.2.0

- [GDB-11761](https://graphwise.atlassian.net/browse/GDB-11761): Adds batch processing of the result documents

  Added functionality where all documents matching the input query, up to a maximum are loaded in memory and returned as
  named graph collection instead of processing one by one.
  When the new functionality is used the plugin is no longer streaming but will require a buffer to store the documents
  until the query is complete.

  Added system property configuration that controls the maximum allowed batch size: `graphdb.mongodb.maxBatchSize`. This
  is to prevent Out-of-Memory problems.

  Updated the GraphDB SDK to stable a version and the Java version to 21.

## 1.1.0

- Makes the plugin compatible with GraphDB 11 and the new version of RDF4J.
- Replaces the JSONLD library.

## 1.0.x

- Legacy versions used prior to GraphDB 11.
