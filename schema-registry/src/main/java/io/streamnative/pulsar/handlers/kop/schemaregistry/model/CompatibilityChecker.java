/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop.schemaregistry.model;

import io.apicurio.registry.rules.compatibility.AvroCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.CompatibilityDifference;
import io.apicurio.registry.rules.compatibility.CompatibilityExecutionResult;
import io.apicurio.registry.rules.compatibility.CompatibilityLevel;
import io.apicurio.registry.rules.compatibility.JsonSchemaCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.NoopCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.ProtobufCompatibilityChecker;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class CompatibilityChecker {

    public enum Mode {
        NONE,
        BACKWARD,
        BACKWARD_TRANSITIVE,
        FORWARD,
        FORWARD_TRANSITIVE,
        FULL,
        FULL_TRANSITIVE;

        public static final Collection<Mode> SUPPORTED_FOR_PROTOBUF =
                Collections.unmodifiableCollection(Arrays.asList(BACKWARD, BACKWARD_TRANSITIVE, NONE));
    }

    /**
     * Verify the compatibility of a Schema, following the request mode
     * @param schema
     * @param subject
     * @param schemaStorage
     * @return
     */
    public static CompletableFuture<Boolean> verify(Schema schema, String subject, SchemaStorage schemaStorage) {
        log.info("verify {} {}", subject, schema.getSchemaDefinition());
        CompletableFuture<CompatibilityChecker.Mode> mode = schemaStorage.getCompatibilityMode(subject);
        return mode.thenCompose(m -> {
            return verifyCompatibility(schema, subject, schemaStorage, m);
        });
    }

    private static CompletableFuture<Boolean> verifyCompatibility(Schema schema, String subject,
                                                                          SchemaStorage schemaStorage, Mode mode) {
        log.info("verify {} {} mode ", subject, mode);
        if (mode == Mode.NONE) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<List<Integer>> versions = schemaStorage.getAllVersionsForSubject(subject);
        return versions.thenCompose(vv -> {
            return verifyCompatibility(schema, schemaStorage, mode, vv);
        });
    }

    private static CompletableFuture<Boolean> verifyCompatibility(Schema schema, SchemaStorage schemaStorage,
                                                                          Mode mode, List<Integer> versions) {
        if (versions.isEmpty()) {
            // no versions ?
            return CompletableFuture.completedFuture(true);
        }
        final List<Integer> idsToCheck;
        if (mode == Mode.BACKWARD || mode == Mode.FORWARD) {
            // only latest
            idsToCheck = Arrays.asList(versions.stream().mapToInt(Integer::intValue).max().getAsInt());
        } else {
            // all the versions
            idsToCheck = versions;
        }
        log.info("Compare schema against {} ids", idsToCheck);

        CompletableFuture<List<Schema>>
                res = schemaStorage.downloadSchemas(idsToCheck);

        return res.thenApply((downloadedSchemas) -> {
            return verify(schema.getSchemaDefinition(), schema.getType(), mode, downloadedSchemas);
        });
    }


    public static boolean verify(String schemaDefinition, String type, Mode mode, List<Schema> allSchemas) {
        if (allSchemas.isEmpty()) {
            return true;
        }
        io.apicurio.registry.rules.compatibility.CompatibilityChecker checker = createChecker(type);
        boolean onlyLatest = false;
        CompatibilityLevel level;
        switch (mode) {
            case BACKWARD:
                 level  = CompatibilityLevel.BACKWARD;
                onlyLatest = true;
                 break;
            case BACKWARD_TRANSITIVE:
                level  = CompatibilityLevel.BACKWARD_TRANSITIVE;
                break;
            case FORWARD:
                level  = CompatibilityLevel.FORWARD;
                onlyLatest = true;
                break;
            case FORWARD_TRANSITIVE:
                level  = CompatibilityLevel.FORWARD_TRANSITIVE;
                break;
            case FULL:
                level  = CompatibilityLevel.FULL;
                onlyLatest = true;
                break;
            case FULL_TRANSITIVE:
                level  = CompatibilityLevel.FULL_TRANSITIVE;
                break;
            default:
                level = CompatibilityLevel.NONE;
                onlyLatest = true;
                break;
        }
        List<String> schemas = allSchemas
                .stream()
                .sorted(Comparator.comparingInt(Schema::getId))
                .map(Schema::getSchemaDefinition)
                .collect(Collectors.toList());
        if (onlyLatest) {
            // only latest
            schemas = schemas.subList(schemas.size() -1, schemas.size());
        }
        log.info("New schema {}", schemaDefinition);
        for (String s : schemas) {
            log.info("Existing schema {}", s);
        }
        try {
            CompatibilityExecutionResult compatibilityExecutionResult =
                    checker.testCompatibility(level, schemas, schemaDefinition);
            log.info("CompatibilityExecutionResult {}", compatibilityExecutionResult.isCompatible());
            if (!compatibilityExecutionResult.isCompatible()) {
                for (CompatibilityDifference error : compatibilityExecutionResult.getIncompatibleDifferences()) {
                    log.info("CompatibilityExecutionResult error {}", error);
                }
            }
            return compatibilityExecutionResult.isCompatible();
        } catch (java.lang.IllegalStateException notSupported) {
            return false;
        }
    }


    private static io.apicurio.registry.rules.compatibility.CompatibilityChecker createChecker(String type) {
        switch (type) {
            case Schema.TYPE_AVRO:
                return new AvroCompatibilityChecker();
            case Schema.TYPE_JSON:
                return new JsonSchemaCompatibilityChecker();
            case Schema.TYPE_PROTOBUF:
                return new ProtobufCompatibilityChecker();
            default:
                return new NoopCompatibilityChecker();
        }
    }

    public static final class IncompatibleSchemaChangeException extends RuntimeException {
        public IncompatibleSchemaChangeException(String message) {
            super(message);
        }
    }

}
