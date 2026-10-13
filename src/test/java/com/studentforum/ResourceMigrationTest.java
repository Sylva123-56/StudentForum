package com.studentforum;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

/** Verifies the compatibility migration can repair a resources table created without textbook/exam. */
class ResourceMigrationTest {
    @Test void compatibilityMigrationContainsIdempotentMetadataRepair(){
        String sql;
        try { sql=Files.readString(Path.of("src/main/resources/db/migration/V13__knowledge_resource_metadata.sql")); }
        catch(Exception e){ throw new AssertionError(e); }
        assertTrue(sql.contains("textbook"));assertTrue(sql.contains("exam"));assertTrue(sql.contains("information_schema.columns"));assertTrue(sql.contains("PREPARE"));
    }
}
