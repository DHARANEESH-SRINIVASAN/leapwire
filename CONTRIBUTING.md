# Contributing to Leapwire

Thanks for your interest. Bug reports, benchmark results from other machines and networks, and pull requests are all welcome.

## Reporting bugs and asking for features

Open an issue with the bug or feature template. For bugs, include the Java, Spring Boot and browser or React Native versions, the endpoints of the screen, and the requests with their `Leapwire-*` headers. Security issues go through [SECURITY.md](SECURITY.md), not public issues.

## Building

Requirements: JDK 17 or newer (CI runs 17, 21 and 25), Maven 3.9, Node 22.

```sh
mvn verify
cd js && npm ci && npm test && npm run typecheck -w leapwire
```

## Pull requests

- One change per pull request, with tests. New behaviour needs a test in the module it touches.
- [docs/PROTOCOL.md](docs/PROTOCOL.md) is the contract between the server and the client. A change to it needs tests on both sides in the same pull request.
- Keep the existing conventions, and add no new runtime dependencies without discussion.
- Performance claims need numbers: run `bench/run.mjs` before and after and put both in the pull request.
- CI must be green on all three JDKs and on Node.

## Releases

Maintainers tag `vX.Y.Z`. The release workflow publishes to Maven Central and npm, attaches signed artifacts and provenance to a GitHub Release, and uses the matching section of [CHANGELOG.md](CHANGELOG.md) as the notes.
