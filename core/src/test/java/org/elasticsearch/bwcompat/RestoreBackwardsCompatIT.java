/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.bwcompat;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsResponse;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.routing.allocation.decider.FilterAllocationDecider;
import org.elasticsearch.common.io.FileTestUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.repositories.fs.FsRepository;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.snapshots.AbstractSnapshotIntegTestCase;
import org.elasticsearch.snapshots.RestoreInfo;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.snapshots.SnapshotRestoreException;
import org.elasticsearch.snapshots.mockstore.MockRepository;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.VersionUtils;
import org.junit.BeforeClass;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

@ClusterScope(scope = Scope.TEST)
public class RestoreBackwardsCompatIT extends AbstractSnapshotIntegTestCase {

    private static Path repoPath;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(Environment.PATH_REPO_SETTING.getKey(), repoPath)
            .build();
    }

    @BeforeClass
    public static void repoSetup() throws IOException {
        repoPath = createTempDir("repositories");
    }

    public void testRestoreOldSnapshots() throws Exception {
        String repo = "test_repo";
        String snapshot = "test_1";
        List<String> repoVersions = repoVersions();
        assertThat(repoVersions.size(), greaterThan(0));
        for (String version : repoVersions) {
            createRepo("repo", version, repo);
            testOldSnapshot(version, repo, snapshot);
        }

        SortedSet<String> expectedVersions = new TreeSet<>();
        for (Version v : VersionUtils.allReleasedVersions()) {
            if (VersionUtils.isSnapshot(v)) continue;  // snapshots are unreleased, so there is no backcompat yet
            if (v.isRelease() == false) continue; // no guarantees for prereleases
            if (v.before(Version.CURRENT.minimumIndexCompatibilityVersion())) continue; // we only support versions N and N-1
            if (v.equals(Version.CURRENT)) continue; // the current version is always compatible with itself
            expectedVersions.add(v.toString());
        }

        for (String repoVersion : repoVersions) {
            if (expectedVersions.remove(repoVersion) == false) {
                logger.warn("Old repositories tests contain extra repo: {}", repoVersion);
            }
        }
        if (expectedVersions.isEmpty() == false) {
            StringBuilder msg = new StringBuilder("Old repositories tests are missing versions:");
            for (String expected : expectedVersions) {
                msg.append("\n" + expected);
            }
            fail(msg.toString());
        }
    }

    public void testRestoreUnsupportedSnapshots() throws Exception {
        String repo = "test_repo";
        String snapshot = "test_1";
        List<String> repoVersions = unsupportedRepoVersions();
        assertThat(repoVersions.size(), greaterThan(0));
        for (String version : repoVersions) {
            createRepo("unsupportedrepo", version, repo);
            assertUnsupportedIndexFailsToRestore(repo, snapshot);
        }
    }

    private List<String> repoVersions() throws Exception {
        return listRepoVersions("repo");
    }

    private List<String> unsupportedRepoVersions() throws Exception {
        return listRepoVersions("unsupportedrepo");
    }

    private List<String> listRepoVersions(String prefix) throws Exception {
        List<String> repoVersions = new ArrayList<>();
        Path repoFiles = getBwcIndicesPath();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoFiles, prefix + "-*.zip")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                String version = fileName.substring(prefix.length() + 1);
                version = version.substring(0, version.length() - ".zip".length());
                repoVersions.add(version);
            }
        }
        return repoVersions;
    }

    private void createRepo(String prefix, String version, String repo) throws Exception {
        Path repoFileFromBuild = getBwcIndicesPath().resolve(prefix + "-" + version + ".zip");
        String repoFileName = repoFileFromBuild.getFileName().toString().split(".zip")[0];
        Path fsRepoPath = repoPath.resolve(repoFileName);
        FileTestUtils.unzip(repoFileFromBuild, fsRepoPath, null);
        logger.info("-->  creating repository [{}] for version [{}]", repo, version);
        assertAcked(client().admin().cluster().preparePutRepository(repo)
            .setType(MockRepository.TYPE).setSettings(Settings.builder()
                .put(FsRepository.REPOSITORIES_LOCATION_SETTING.getKey(), fsRepoPath.getParent().relativize(fsRepoPath).resolve("repo").toString())));
    }

    private void testOldSnapshot(String version, String repo, String snapshot) throws IOException {
        logger.info("--> get snapshot and check its version");
        GetSnapshotsResponse getSnapshotsResponse = client().admin().cluster().prepareGetSnapshots(repo).setSnapshots(snapshot).get();
        assertThat(getSnapshotsResponse.getSnapshots().size(), equalTo(1));
        SnapshotInfo snapshotInfo = getSnapshotsResponse.getSnapshots().get(0);
        assertThat(snapshotInfo.version().toString(), equalTo(version));

        logger.info("--> restoring snapshot");
        RestoreSnapshotResponse response = client().admin().cluster().prepareRestoreSnapshot(repo, snapshot).setRestoreGlobalState(true).setWaitForCompletion(true).get();
        assertThat(response.status(), equalTo(RestStatus.OK));
        RestoreInfo restoreInfo = response.getRestoreInfo();
        assertThat(restoreInfo.successfulShards(), greaterThan(0));
        assertThat(restoreInfo.successfulShards(), equalTo(restoreInfo.totalShards()));
        assertThat(restoreInfo.failedShards(), equalTo(0));
        String index = restoreInfo.indices().get(0);

        logger.info("--> check search");
        SearchResponse searchResponse = client().prepareSearch(index).get();
        assertThat(searchResponse.getHits().getTotalHits(), greaterThan(1L));

        logger.info("--> check settings");
        ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
        assertThat(clusterState.metaData().persistentSettings().get(FilterAllocationDecider.CLUSTER_ROUTING_EXCLUDE_GROUP_SETTING.getKey() + "version_attr"), equalTo(version));

        logger.info("--> check templates");
        IndexTemplateMetaData template = clusterState.getMetaData().templates().get("template_" + version.toLowerCase(Locale.ROOT));
        assertThat(template, notNullValue());
        assertThat(template.patterns(), equalTo(Collections.singletonList("te*")));
        assertThat(template.settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, -1), equalTo(1));
        assertThat(template.mappings().size(), equalTo(1));
        assertThat(template.mappings().get("type1").string(),
            anyOf(
                equalTo("{\"type1\":{\"_source\":{\"enabled\":false}}}"),
                equalTo("{\"type1\":{\"_source\":{\"enabled\":\"false\"}}}"),
                equalTo("{\"type1\":{\"_source\":{\"enabled\":\"0\"}}}"),
                equalTo("{\"type1\":{\"_source\":{\"enabled\":0}}}"),
                equalTo("{\"type1\":{\"_source\":{\"enabled\":\"off\"}}}"),
                equalTo("{\"type1\":{\"_source\":{\"enabled\":\"no\"}}}")
            ));
        assertThat(template.aliases().size(), equalTo(3));
        assertThat(template.aliases().get("alias1"), notNullValue());
        assertThat(template.aliases().get("alias2").filter().string(), containsString(version));
        assertThat(template.aliases().get("alias2").indexRouting(), equalTo("kimchy"));
        assertThat(template.aliases().get("{index}-alias"), notNullValue());

        logger.info("--> cleanup");
        cluster().wipeIndices(restoreInfo.indices().toArray(new String[restoreInfo.indices().size()]));
        cluster().wipeTemplates();

    }

    private void assertUnsupportedIndexFailsToRestore(String repo, String snapshot) throws IOException {
        logger.info("--> restoring unsupported snapshot");
        try {
            client().admin().cluster().prepareRestoreSnapshot(repo, snapshot).setRestoreGlobalState(true).setWaitForCompletion(true).get();
            fail("should have failed to restore - " + repo);
        } catch (SnapshotRestoreException ex) {
            assertThat(ex.getMessage(), containsString("snapshot does not exist"));
        }
    }
}

