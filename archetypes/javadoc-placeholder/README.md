# Synapse archetype

This jar is the javadoc jar of a Synapse Maven archetype (`synapse-archetype-quickstart` or `synapse-archetype-service`).
An archetype has no Java API, so it holds no Javadoc: Maven Central requires a javadoc jar for every artifact that isn't
a pom. Its `META-INF/maven` directory names the archetype.

Generate a project with the archetype:

    mvn archetype:generate -DarchetypeGroupId=io.americanexpress.synapse \
        -DarchetypeArtifactId=<archetype> -DarchetypeVersion=<version>

* `synapse-archetype-quickstart`: a REST service in one module, shaped like the `hello-rest` sample.
* `synapse-archetype-service`: a service in three modules (contract, data and service), served over REST and gRPC on
  PostgreSQL, shaped like the `reference-bookstore` sample. It also asks for `-DserviceName=<name>`: a lowercase letter
  followed by lowercase letters and digits, with no hyphen and not a Java keyword (`orders`, not `order-service`),
  because it names a Java package and environment variables. Generation fails otherwise.

The generated project's README says how to run, test and change it. Synapse's documentation:
https://github.com/americanexpress/synapse
