# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 0.1.x | Yes |

## Security contact

Dharaneesh S, maintainer: GitHub [@DHARANEESH-SRINIVASAN](https://github.com/DHARANEESH-SRINIVASAN). Reach the maintainer privately through the reporting link below, which is monitored.

## Reporting a vulnerability

Report vulnerabilities privately at https://github.com/DHARANEESH-SRINIVASAN/leapwire/security/advisories/new (the repository's **Security** tab, **Report a vulnerability**). Please do not open a public issue, pull request or discussion for a suspected vulnerability.

Include the affected version, the requests or the learned rule that trigger the problem, and the impact you observed.

## Coordinated disclosure

- Acknowledgement within 3 days and a first assessment within 7 days.
- Confirmed vulnerabilities are fixed in a patch release, normally within 30 days, and disclosed through a GitHub security advisory with a CVE when one applies.
- The reporter is credited in the advisory unless they ask not to be.
- Please keep the details private until the advisory is published, or until 90 days after the report, whichever comes first.

## Security model

- Speculative requests are real HTTP requests from the server to itself. They carry only the `Authorization`, `Cookie` and `Accept-Language` headers of the request that caused them, and pass through every servlet filter and Spring Security rule, so a bundle can only contain what that client could fetch itself. A target the client may not read comes back as its status code without a body.
- Only GET requests are speculated. Endpoints annotated `@LeapwireSkip` are never learned from, never speculated and refuse speculative requests.
- A rule needs pages from several distinct users before it is used, so one client cannot make the server speculate for others. Rules and pages live only in the server's memory.
- Pushed responses live only in the memory of the client that received them, are served once and expire after the TTL.
- Report anything that lets a bundle carry data its client could not fetch directly, or that turns speculation into a way to amplify load, as a vulnerability.

## How releases are protected

- Maven Central artifacts are signed with the release key `B9F0 51CF 9545 B2AB 7282 9518 908A 18CE E22E 2846`, published on keys.openpgp.org and keyserver.ubuntu.com.
- The npm package is published from GitHub Actions with npm provenance, recorded in the Sigstore transparency log.
- Release jars and the npm tarball carry GitHub build-provenance attestations: `gh attestation verify <file> -R DHARANEESH-SRINIVASAN/leapwire`.
- Every GitHub Action is pinned to a commit SHA, workflows run with read-only tokens by default, and CodeQL, Dependabot and OpenSSF Scorecard run on every change.
