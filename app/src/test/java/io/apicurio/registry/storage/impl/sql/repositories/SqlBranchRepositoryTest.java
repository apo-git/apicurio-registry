package io.apicurio.registry.storage.impl.sql.repositories;

import io.apicurio.registry.model.BranchId;
import io.apicurio.registry.model.GA;
import io.apicurio.registry.model.VersionId;
import io.apicurio.registry.storage.dto.BranchMetaDataDto;
import io.apicurio.registry.storage.error.NotAllowedException;
import io.apicurio.registry.storage.error.VersionAlreadyExistsOnBranchException;
import io.apicurio.registry.storage.error.VersionNotFoundException;
import io.apicurio.registry.storage.impl.sql.HandleFactory;
import io.apicurio.registry.storage.impl.sql.SqlStatements;
import io.apicurio.registry.storage.impl.sql.jdb.Handle;
import io.apicurio.registry.storage.impl.sql.jdb.HandleAction;
import io.apicurio.registry.storage.impl.sql.jdb.HandleCallback;
import io.apicurio.registry.storage.impl.sql.jdb.Update;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SqlBranchRepository#replaceBranchVersions} without Quarkus and without a database.
 *
 * <p>Test doubles used:
 * <ul>
 * <li><b>Fake</b>: {@link FakeHandleFactory}, a small working implementation of {@link HandleFactory} that
 * passes a prepared handle to the callback (no connection pool, no transaction).</li>
 * <li><b>Stubs</b>: {@link SqlStatements} returns fixed SQL strings and fixed answers for
 * {@code isPrimaryKeyViolation} / {@code isForeignKeyViolation}; {@link SecurityIdentity} returns a fixed
 * user.</li>
 * <li><b>Mocks</b>: {@link Handle} and {@link Update}, whose interactions are verified (which statements were
 * created, which values were bound, whether {@code execute()} was called).</li>
 * <li><b>Spy</b>: the repository itself, with {@code getBranchMetaData} replaced so the test does not need a
 * query result mapper.</li>
 * </ul>
 */
class SqlBranchRepositoryTest {

    private static final String DELETE_SQL = "DELETE_BRANCH_VERSIONS";
    private static final String INSERT_SQL = "INSERT_BRANCH_VERSION";
    private static final String UPDATE_MODIFIED_SQL = "UPDATE_BRANCH_MODIFIED_TIME";

    private static final GA GA_ = new GA("my-group", "my-artifact");
    private static final BranchId BRANCH = new BranchId("my-branch");

    private SqlStatements sqlStatements;
    private Handle handle;
    private Update deleteUpdate;
    private Update insertUpdate;
    private Update modifiedUpdate;
    private SqlBranchRepository repository;

    /**
     * Fake: a real, simplified implementation of {@link HandleFactory}. It has working behavior (it runs the
     * callback), but takes a shortcut that makes it unsuitable for production: no connection, no transaction.
     */
    static class FakeHandleFactory implements HandleFactory {

        private final Handle handle;

        FakeHandleFactory(Handle handle) {
            this.handle = handle;
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            return callback.withHandle(handle);
        }

        @Override
        public <R, X extends Exception> R withHandleNoException(HandleCallback<R, X> callback) {
            try {
                return callback.withHandle(handle);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <X extends Exception> void withHandleNoException(HandleAction<X> callback) {
            try {
                callback.withHandle(handle);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        // Stub: fixed SQL strings, so each statement gets its own Update mock
        sqlStatements = mock(SqlStatements.class);
        when(sqlStatements.deleteBranchVersions()).thenReturn(DELETE_SQL);
        when(sqlStatements.insertBranchVersion()).thenReturn(INSERT_SQL);
        when(sqlStatements.updateBranchModifiedTime()).thenReturn(UPDATE_MODIFIED_SQL);

        // Mocks: bind(...) returns the same Update (fluent API), execute() returns 0 unless stubbed
        deleteUpdate = fluentUpdateMock();
        insertUpdate = fluentUpdateMock();
        modifiedUpdate = fluentUpdateMock();

        handle = mock(Handle.class);
        when(handle.createUpdate(DELETE_SQL)).thenReturn(deleteUpdate);
        when(handle.createUpdate(INSERT_SQL)).thenReturn(insertUpdate);
        when(handle.createUpdate(UPDATE_MODIFIED_SQL)).thenReturn(modifiedUpdate);

        // Stub: fixed user for the "modified by" column
        SecurityIdentity securityIdentity = mock(SecurityIdentity.class);
        when(securityIdentity.getPrincipal()).thenReturn(() -> "test-user");

        // Collaborators not used by replaceBranchVersions are null
        SqlBranchRepository real = new SqlBranchRepository(new FakeHandleFactory(handle), sqlStatements, null,
                securityIdentity, null, null, null);

        // Spy: the real repository, with only getBranchMetaData replaced
        repository = spy(real);
        doReturn(branch(false)).when(repository).getBranchMetaData(any(), any());
    }

    @Test
    void shouldInsertVersionsInOrderAndUpdateModifiedTime() {
        repository.replaceBranchVersions(GA_, BRANCH, versions("1", "2"));

        InOrder order = inOrder(deleteUpdate, insertUpdate, modifiedUpdate);
        order.verify(deleteUpdate).execute();
        order.verify(insertUpdate).bind(3, (Integer) 0);
        order.verify(insertUpdate).bind(4, "1");
        order.verify(insertUpdate).bind(3, (Integer) 1);
        order.verify(insertUpdate).bind(4, "2");
        order.verify(modifiedUpdate).bind(0, "test-user");
        order.verify(modifiedUpdate).execute();
    }

    @Test
    void shouldMapPrimaryKeyViolationToVersionAlreadyExistsOnBranch() {
        RuntimeException dbError = new RuntimeException("duplicate key");
        when(insertUpdate.execute()).thenReturn(1).thenThrow(dbError);
        when(sqlStatements.isPrimaryKeyViolation(dbError)).thenReturn(true);

        VersionAlreadyExistsOnBranchException thrown = assertThrows(VersionAlreadyExistsOnBranchException.class,
                () -> repository.replaceBranchVersions(GA_, BRANCH, versions("1", "1")));

        assertEquals("1", thrown.getVersion());
        assertEquals("my-branch", thrown.getBranchId());
        verify(sqlStatements, never()).isForeignKeyViolation(any());
        verify(modifiedUpdate, never()).execute();
    }

    @Test
    void shouldMapForeignKeyViolationToVersionNotFound() {
        RuntimeException dbError = new RuntimeException("foreign key violation");
        when(insertUpdate.execute()).thenReturn(1).thenThrow(dbError);
        when(sqlStatements.isPrimaryKeyViolation(dbError)).thenReturn(false);
        when(sqlStatements.isForeignKeyViolation(dbError)).thenReturn(true);

        VersionNotFoundException thrown = assertThrows(VersionNotFoundException.class,
                () -> repository.replaceBranchVersions(GA_, BRANCH, versions("1", "999")));

        assertEquals("999", thrown.getVersion());
        verify(modifiedUpdate, never()).execute();
    }

    @Test
    void shouldRethrowOtherDatabaseErrors() {
        RuntimeException dbError = new IllegalStateException("connection lost");
        when(insertUpdate.execute()).thenThrow(dbError);
        when(sqlStatements.isPrimaryKeyViolation(dbError)).thenReturn(false);
        when(sqlStatements.isForeignKeyViolation(dbError)).thenReturn(false);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> repository.replaceBranchVersions(GA_, BRANCH, versions("1")));

        assertSame(dbError, thrown);
        verify(sqlStatements).isPrimaryKeyViolation(dbError);
        verify(sqlStatements).isForeignKeyViolation(dbError);
        verify(modifiedUpdate, never()).execute();
    }

    @Test
    void shouldRejectSystemDefinedBranchWithoutTouchingTheDatabase() {
        doReturn(branch(true)).when(repository).getBranchMetaData(any(), any());

        assertThrows(NotAllowedException.class,
                () -> repository.replaceBranchVersions(GA_, BranchId.LATEST, versions("1")));

        verifyNoInteractions(handle);
    }

    /**
     * Mock of {@link Update} whose {@code bind(...)} calls return the mock itself, so the fluent chain
     * {@code createUpdate(..).bind(..).bind(..).execute()} works. Other methods return Mockito defaults.
     */
    private static Update fluentUpdateMock() {
        return mock(Update.class, invocation -> "bind".equals(invocation.getMethod().getName())
                ? invocation.getMock() : RETURNS_DEFAULTS.answer(invocation));
    }

    private static BranchMetaDataDto branch(boolean systemDefined) {
        return BranchMetaDataDto.builder().groupId("my-group").artifactId("my-artifact")
                .branchId("my-branch").systemDefined(systemDefined).build();
    }

    private static List<VersionId> versions(String... versions) {
        return Arrays.stream(versions).map(VersionId::new).toList();
    }
}
