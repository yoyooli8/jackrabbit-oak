/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.jcr;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.jcr.ItemExistsException;
import javax.jcr.PathNotFoundException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Workspace;
import javax.jcr.lock.LockManager;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.observation.ObservationManager;
import javax.jcr.query.QueryManager;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.api.security.authorization.PrivilegeManager;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.AuthInfo;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.ContentSession;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.QueryEngine;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.TreeLocation;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.namepath.LocalNameMapper;
import org.apache.jackrabbit.oak.namepath.NameMapper;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.namepath.NamePathMapperImpl;
import org.apache.jackrabbit.oak.plugins.identifier.IdentifierManager;
import org.apache.jackrabbit.oak.plugins.name.Namespaces;
import org.apache.jackrabbit.oak.plugins.nodetype.DefinitionProvider;
import org.apache.jackrabbit.oak.plugins.nodetype.EffectiveNodeTypeProvider;
import org.apache.jackrabbit.oak.plugins.observation.ObservationManagerImpl;
import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.plugins.value.ValueFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import static com.google.common.base.Preconditions.checkNotNull;

public class SessionDelegate {
    static final Logger log = LoggerFactory.getLogger(SessionDelegate.class);

    private final NamePathMapper namePathMapper;
    private final Repository repository;
    private final ScheduledExecutorService executor;
    private final ContentSession contentSession;
    private final ValueFactoryImpl valueFactory;
    private final Workspace workspace;
    private final SessionImpl session;
    private final Root root;
    private final boolean autoRefresh;

    private final SecurityProvider securityProvider;

    private final IdentifierManager idManager;

    private ObservationManagerImpl observationManager;
    private PrincipalManager principalManager;
    private UserManager userManager;
    private PrivilegeManager privilegeManager;
    private boolean isAlive = true;
    private int sessionOpCount;
    private int revision;

    SessionDelegate(
            Repository repository, ScheduledExecutorService executor,
            ContentSession contentSession, SecurityProvider securityProvider,
            boolean autoRefresh) {

        this.repository = checkNotNull(repository);
        this.executor = executor;
        this.contentSession = checkNotNull(contentSession);
        this.securityProvider = securityProvider;
        this.autoRefresh = autoRefresh;

        this.root = contentSession.getLatestRoot();
        // FIXME: do not pass partially initialized 'this'
        this.workspace = new WorkspaceImpl(this);

        Map<String, String> namespaces = Maps.newHashMap();
        NameMapper mapper = new LocalNameMapper(namespaces) {
            @Override
            protected Map<String, String> getNamespaceMap() {
                return Namespaces.getNamespaceMap(root.getTree("/"));
            }
        };
        this.session = new SessionImpl(this, namespaces);
        this.idManager = new IdentifierManager(root);
        this.namePathMapper = new NamePathMapperImpl(mapper, idManager);
        this.valueFactory = new ValueFactoryImpl(root.getBlobFactory(), namePathMapper);
    }

    /**
     * Performs the passed {@code SessionOperation} in a safe execution context. This
     * context ensures that the session is refreshed if necessary and that refreshing
     * occurs before the session operation is performed and the refreshing is done only
     * once.
     *
     * @param sessionOperation  the {@code SessionOperation} to perform
     * @param <T>  return type of {@code sessionOperation}
     * @return  the result of {@code sessionOperation.perform()}
     * @throws RepositoryException
     */
    public <T> T perform(SessionOperation<T> sessionOperation) throws RepositoryException {
        try {
            sessionOpCount++;
            if (needsRefresh()) {
                refresh(true);
            }
            return sessionOperation.perform();
        } finally {
            sessionOpCount--;
        }
    }

    private boolean needsRefresh() {
        // Refresh is always needed if this is an auto refresh session. Otherwise
        // refresh in only needed for non re-entrant session operations and only if
        // observation events have actually been delivered
        return autoRefresh ||
                (sessionOpCount <= 1 && observationManager != null && observationManager.hasEvents());
    }

    /**
     * Revision of this session. The revision is incremented each time a session is refreshed or saved.
     * This allows items to determine whether they need to re-resolve their underlying state when the
     * revision on which an item is based does not match the revision of the session any more.
     * @return  the current revision of this session
     */
    int getRevision() {
        return revision;
    }

    public boolean isAlive() {
        return isAlive;
    }

    @Nonnull
    public Session getSession() {
        return session;
    }

    @Nonnull
    public AuthInfo getAuthInfo() {
        return contentSession.getAuthInfo();
    }

    @Nonnull
    public Repository getRepository() {
        return repository;
    }

    public void logout() {
        if (!isAlive) {
            // ignore
            return;
        }

        isAlive = false;
        if (observationManager != null) {
            observationManager.dispose();
        }
        // TODO

        try {
            contentSession.close();
        } catch (IOException e) {
            log.warn("Error while closing connection", e);
        }
    }

    @CheckForNull
    public NodeDelegate getRootNode() {
        return getNode("/");
    }
    
    @CheckForNull
    public Root getRoot() {
        return root;
    }

    /**
     * {@code NodeDelegate} at the given path
     * @param path Oak path
     * @return  The {@code NodeDelegate} at {@code path} or {@code null} if
     * none exists or not accessible.
     */
    @CheckForNull
    public NodeDelegate getNode(String path) {
        return NodeDelegate.create(this, getLocation(path));
    }

    @CheckForNull
    public NodeDelegate getNodeByIdentifier(String id) {
        Tree tree = idManager.getTree(id);
        return (tree == null) ? null : new NodeDelegate(this, tree);
    }

    @CheckForNull
    /**
     * {@code PropertyDelegate} at the given path
     * @param path Oak path
     * @return  The {@code PropertyDelegate} at {@code path} or {@code null} if
     * none exists or not accessible.
     */
    public PropertyDelegate getProperty(String path) {
        TreeLocation location = root.getLocation(path);
        return location.getProperty() == null
            ? null
            : new PropertyDelegate(this, location);
    }

    @Nonnull
    public ValueFactoryImpl getValueFactory() {
        return valueFactory;
    }

    @Nonnull
    public NamePathMapper getNamePathMapper() {
        return namePathMapper;
    }

    public boolean hasPendingChanges() {
        return root.hasPendingChanges();
    }

    public void save() throws RepositoryException {
        try {
            root.commit();
            revision++;
        } catch (CommitFailedException e) {
            e.throwRepositoryException();
        }
    }

    public void refresh(boolean keepChanges) {
        if (keepChanges) {
            root.rebase();
        } else {
            root.refresh();
        }
        revision++;
    }

    /**
     * Returns the Oak name for the given JCR name, or throws a
     * {@link RepositoryException} if the name is invalid or can
     * otherwise not be mapped.
     *
     * @param jcrName JCR name
     * @return Oak name
     * @throws RepositoryException if the name is invalid
     */
    @Nonnull
    public String getOakName(String jcrName) throws RepositoryException {
        return getNamePathMapper().getOakName(jcrName);
    }

    /**
     * Shortcut for {@code SessionDelegate.getNamePathMapper().getOakPath(jcrPath)}.
     *
     * @param jcrPath JCR path
     * @return Oak path, or {@code null}
     */
    @CheckForNull
    public String getOakPathOrNull(String jcrPath) {
        return getNamePathMapper().getOakPath(jcrPath);
    }

    /**
     * Shortcut for {@code SessionDelegate.getOakPathKeepIndex(jcrPath)}.
     *
     * @param jcrPath JCR path
     * @return Oak path, or {@code null}, with indexes left intact
     * @throws PathNotFoundException 
     */
    @Nonnull
    public String getOakPathKeepIndexOrThrowNotFound(String jcrPath) throws PathNotFoundException {
        String oakPath = getNamePathMapper().getOakPathKeepIndex(jcrPath);
        if (oakPath != null) {
            return oakPath;
        } else {
            throw new PathNotFoundException(jcrPath);
        }
    }

    /**
     * Returns the Oak path for the given JCR path, or throws a
     * {@link PathNotFoundException} if the path can not be mapped.
     *
     * @param jcrPath JCR path
     * @return Oak path
     * @throws PathNotFoundException if the path can not be mapped
     */
    @Nonnull
    public String getOakPathOrThrowNotFound(String jcrPath) throws PathNotFoundException {
        String oakPath = getOakPathOrNull(jcrPath);
        if (oakPath != null) {
            return oakPath;
        } else {
            throw new PathNotFoundException(jcrPath);
        }
    }

    /**
     * Returns the Oak path for the given JCR path, or throws a
     * {@link RepositoryException} if the path can not be mapped.
     *
     * @param jcrPath JCR path
     * @return Oak path
     * @throws RepositoryException if the path can not be mapped
     */
    @Nonnull
    public String getOakPath(String jcrPath)
            throws RepositoryException {
        String oakPath = getOakPathOrNull(jcrPath);
        if (oakPath != null) {
            return oakPath;
        } else {
            throw new RepositoryException("Invalid name or path: " + jcrPath);
        }
    }

    //----------------------------------------------------------< Workspace >---

    @Nonnull
    public Workspace getWorkspace() {
        return workspace;
    }

    @Nonnull
    public String getWorkspaceName() {
        return contentSession.getWorkspaceName();
    }

    /**
     * Copy a node
     * @param srcPath  oak path to the source node to copy
     * @param destPath  oak path to the destination
     * @throws RepositoryException
     */
    public void copy(String srcPath, String destPath) throws RepositoryException {
        // check destination
        Tree dest = getTree(destPath);
        if (dest != null) {
            throw new ItemExistsException(destPath);
        }

        // check parent of destination
        String destParentPath = PathUtils.getParentPath(destPath);
        Tree destParent = getTree(destParentPath);
        if (destParent == null) {
            throw new PathNotFoundException(PathUtils.getParentPath(destPath));
        }

        // check source exists
        Tree src = getTree(srcPath);
        if (src == null) {
            throw new PathNotFoundException(srcPath);
        }

        try {
            Root currentRoot = contentSession.getLatestRoot();
            currentRoot.copy(srcPath, destPath);
            currentRoot.commit();
        }
        catch (CommitFailedException e) {
            e.throwRepositoryException();
        }
    }

    /**
     * Move a node
     * @param srcPath  oak path to the source node to copy
     * @param destPath  oak path to the destination
     * @param transientOp  whether or not to perform the move in transient space
     * @throws RepositoryException
     */
    public void move(String srcPath, String destPath, boolean transientOp)
            throws RepositoryException {

        Root moveRoot = transientOp ? root : contentSession.getLatestRoot();

        // check destination
        Tree dest = moveRoot.getTree(destPath);
        if (dest != null) {
            throw new ItemExistsException(destPath);
        }

        // check parent of destination
        String destParentPath = PathUtils.getParentPath(destPath);
        Tree destParent = moveRoot.getTree(destParentPath);
        if (destParent == null) {
            throw new PathNotFoundException(PathUtils.getParentPath(destPath));
        }

        // check source exists
        Tree src = moveRoot.getTree(srcPath);
        if (src == null) {
            throw new PathNotFoundException(srcPath);
        }

        try {
            moveRoot.move(srcPath, destPath);
            if (!transientOp) {
                moveRoot.commit();
            }
        } catch (CommitFailedException e) {
            e.throwRepositoryException();
        }
    }

    @Nonnull
    public LockManager getLockManager() throws RepositoryException {
        return workspace.getLockManager();
    }

    @Nonnull
    public QueryEngine getQueryEngine() {
        return root.getQueryEngine();
    }

    @Nonnull
    public QueryManager getQueryManager() throws RepositoryException {
        return workspace.getQueryManager();
    }

    @Nonnull
    public NodeTypeManager getNodeTypeManager() throws RepositoryException {
        return workspace.getNodeTypeManager();
    }

    @Nonnull
    public VersionManager getVersionManager() throws RepositoryException {
        return workspace.getVersionManager();
    }

    @Nonnull
    public ObservationManager getObservationManager() {
        if (observationManager == null) {
            observationManager = new ObservationManagerImpl(getRoot(), getNamePathMapper(), executor);
        }
        return observationManager;
    }

    public IdentifierManager getIdManager() {
        return idManager;
    }

    @Nonnull
    public ContentSession getContentSession() {
        return contentSession;
    }

    //-----------------------------------------------------------< internal >---

    /**
     * Get the {@code Tree} with the given path
     * @param path  oak path
     * @return  tree at the given path or {@code null} if no such tree exists or
     * if the tree at {@code path} is not accessible.
     */
    @CheckForNull
    Tree getTree(String path) {
        return root.getTree(path);
    }

    @Nonnull
    TreeLocation getLocation(String path) {
        return root.getLocation(path);
    }

    @Nonnull
    PrincipalManager getPrincipalManager() throws RepositoryException {
        if (principalManager == null) {
            if (securityProvider != null) {
                principalManager = securityProvider.getPrincipalConfiguration().getPrincipalManager(session, root, getNamePathMapper());
            } else {
                throw new UnsupportedRepositoryOperationException("Principal management not supported.");
            }
        }
        return principalManager;
    }

    @Nonnull
    UserManager getUserManager() throws UnsupportedRepositoryOperationException {
        if (userManager == null) {
            if (securityProvider != null) {
                userManager = securityProvider.getUserConfiguration().getUserManager(root, getNamePathMapper());
            } else {
                throw new UnsupportedRepositoryOperationException("User management not supported.");
            }
        }
        return userManager;
    }

    @Nonnull
    PrivilegeManager getPrivilegeManager() throws UnsupportedRepositoryOperationException {
        if (privilegeManager == null) {
            if (securityProvider != null) {
                privilegeManager = securityProvider.getPrivilegeConfiguration().getPrivilegeManager(root, getNamePathMapper());
            } else {
                throw new UnsupportedRepositoryOperationException("Privilege management not supported.");
            }
        }
        return privilegeManager;
    }

    @Nonnull
    EffectiveNodeTypeProvider getEffectiveNodeTypeProvider() throws RepositoryException {
        return (EffectiveNodeTypeProvider) workspace.getNodeTypeManager();
    }

    @Nonnull
    DefinitionProvider getDefinitionProvider() throws RepositoryException {
        return (DefinitionProvider) workspace.getNodeTypeManager();
    }

    void checkProtectedNodes(String... absJcrPaths) throws RepositoryException {
        for (String absPath : absJcrPaths) {
            NodeImpl node = (NodeImpl) session.getNode(absPath);
            node.checkProtected();
        }
    }
}
