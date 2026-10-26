# Contributing to Besu Stateless

Thanks for taking the time to contribute!

This repository hosts the [EIP-8297](https://eips.ethereum.org/EIPS/eip-8297) Partitioned Binary Trie library for Hyperledger Besu. Contributions include code, tests, documentation, benchmarks, and issue reports.

## Getting started

1. Fork [besu-eth/besu-stateless](https://github.com/besu-eth/besu-stateless) on GitHub.
2. Clone your fork and create a feature branch from `main`.
3. Install **Java 21** (Temurin or Corretto recommended).
4. Build and test locally:

   ```bash
   ./gradlew clean test
   ./gradlew compileJmhJava
   ```

5. Format code before committing:

   ```bash
   ./gradlew spotlessApply
   ```

## Code style

- Java 21 toolchain; follow existing package layout under `org.hyperledger.besu.ethereum.partitionedbinarytrie`.
- Spotless enforces Google Java Format, import order (`org.hyperledger` first), and the Apache 2.0 license header.
- Compiler warnings are errors (`-Werror`); Error Prone checks are enabled.
- Keep hot-path code allocation-free where the surrounding code already does (see `internal` and `stored` packages).
- Match existing naming, abstractions, and test patterns — read neighboring code before adding new APIs.

## Tests

- All changes must pass `./gradlew clean test`.
- Add or update tests for behavioral changes. The spec-reference oracle under `src/test/java/.../trie/reference/` is the conformance baseline.
- JMH sources must compile: `./gradlew compileJmhJava`.

## Pull request process

1. Open a PR against `main` with a clear description of the change and motivation.
2. Link related issues (e.g. `fixes #123`).
3. Ensure CI is green.
4. Request review from maintainers (see [MAINTAINERS.md](MAINTAINERS.md)).
5. Address review feedback; keep commits focused or squash before merge per reviewer preference.

Use the [pull request template](.github/pull_request_template.md).

## Developer Certificate of Origin (DCO)

Every commit must include a DCO sign-off. Add this line to your commit message:

```
Signed-off-by: Your Name <your.email@example.com>
```

Or use `git commit -s`. See [DCO.md](DCO.md) and the [Hyperledger DCO policy](https://wiki.hyperledger.org/display/BESU/DCO).

## Code of conduct

Participants must follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Do not open public issues for security vulnerabilities. See [SECURITY.md](SECURITY.md).

## Questions

- [Hyperledger Besu Discord](https://discord.gg/hyperledger)
- [Besu wiki — How to Contribute](https://wiki.hyperledger.org/display/BESU/How+to+Contribute)
