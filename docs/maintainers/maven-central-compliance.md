# Maven Central compliance

This project publishes open-source Java library modules under Apache License
2.0. It has no proprietary service requirement and publishes only the five
consumer modules listed in the release guide.

## Mechanical release requirements

The build enforces the current Central requirements that can be verified from
the repository:

- every main JAR has matching source and Javadoc JARs;
- every main, source, and Javadoc JAR contains the exact repository license at
  `META-INF/LICENSE`;
- each POM has coordinates, name, description, project URL, Apache 2.0 license,
  an identified developer, SCM connections, and transitive dependencies;
- every deployed primary artifact, POM, source JAR, and Javadoc JAR is signed;
- the bundle follows Maven repository layout and contains only the five public
  modules;
- publication uses a Maven Central Portal user token, waits for `VALIDATED`,
  explicitly publishes, and waits for `PUBLISHED`.

`verifyPublishedLicenseFiles` runs as part of every publishable module's
`check`. `verifyCentralStaging` verifies the signed bundle layout.

## Maintainer attestations

Automation cannot accept legal terms or prove ownership. Before each release,
the maintainer must confirm that:

1. the Central account details and namespace authorization remain accurate;
2. the publisher accepts the current Central Terms of Service and Publisher
   Terms;
3. all submitted materials may be distributed under their declared licenses
   and contain no confidential, infringing, harmful, or unlawful material;
4. the applicable publisher tier permits the release;
5. the release evidence and source commit are immutable and final.

Central artifacts are immutable. Correct a published defect with a new version;
never attempt to replace an existing coordinate.

## 1.0.0 audit

The 2026-07-30 audit verified all five `1.0.0` coordinates, POM metadata,
sources, Javadocs, checksums, artifact signatures, and public-key
retrievability. It found that the JAR payloads did not include the Apache 2.0
license text. Version 1.1.0 adds the exact license to all fifteen distributed
JARs and a build-time regression gate.

Authoritative references:

- <https://central.sonatype.org/publish/producer-terms/>
- <https://central.sonatype.org/publish/requirements/>
- <https://central.sonatype.org/publish/publish-portal-api/>
- <https://central.sonatype.org/publish/requirements/immutability/>
