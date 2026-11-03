# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-11-03

### Added
- `leapwire-spring-boot-starter`: learns value bindings between JSON GET responses and later requests on the same screen (path variables, query parameters and comma or repeated lists), activates a rule after 10 pages from 3 users that follow it 90% of the time, and answers the first request of a learned chain with an NDJSON bundle of the main answer, the planned URLs and each follow-up answer. Speculative requests are loopback HTTP requests with the caller's credentials. Limits: 4 levels, 8 requests, 2 seconds. `@LeapwireSkip` excludes endpoints. Requests without the client headers are served byte for byte as without Leapwire.
- npm package `leapwire`: `install()` wraps the global `fetch`, `createFetch()` and `setRoute()` serve React Native. The main answer is returned as soon as it arrives, pushed answers are served once within 10 seconds, and requests wait for planned answers instead of going to the network.
- `leapwire-example`: a Spring Security shop with two users and a six-call order screen, and `bench/`, a headless Chrome benchmark behind a round-trip delay proxy: 945 ms to 294 ms at 150 ms round trip, six requests to one.
- [Protocol v1](docs/PROTOCOL.md).
