/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2008, 2018, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018, Chris Fraire <cfraire@me.com>.
 */

package org.opensolaris.opengrok.search.context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.opensolaris.opengrok.analysis.FileAnalyzer;
import org.opensolaris.opengrok.analysis.plain.PlainAnalyzerFactory;
import org.opensolaris.opengrok.condition.ConditionalRun;
import org.opensolaris.opengrok.condition.ConditionalRunRule;
import org.opensolaris.opengrok.condition.CtagsInstalled;
import org.opensolaris.opengrok.configuration.Project;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.history.HistoryGuru;
import org.opensolaris.opengrok.index.Indexer;
import org.opensolaris.opengrok.util.TestRepository;
import org.opensolaris.opengrok.history.RepositoryFactory;
import org.opensolaris.opengrok.search.QueryBuilder;
import org.opensolaris.opengrok.search.SearchEngine;
import static org.opensolaris.opengrok.util.CustomAssertions.assertLinesEqual;
import org.opensolaris.opengrok.util.FileUtilities;
import org.opensolaris.opengrok.util.IOUtils;

/**
 * Represents a container for tests of {@link SearchEngine} with
 * {@link ContextFormatter} etc. with a non-zero tab-size.
 * <p>
 * Derived from Trond Norbye's {@code SearchEngineTest}
 */
@ConditionalRun(CtagsInstalled.class)
public class SearchAndContextFormatterTest2 {

    private static final int TABSIZE = 8;

    private static final List<File> TEMP_DIRS = new ArrayList<>();
    private static RuntimeEnvironment env;
    private static TestRepository repository1;
    private static TestRepository repository2;
    private static File configFile;
    private static boolean originalProjectsEnabled;

    @Rule
    public ConditionalRunRule rule = new ConditionalRunRule();

    @BeforeClass
    public static void setUpClass() throws Exception {
        env = RuntimeEnvironment.getInstance();

        originalProjectsEnabled = env.isProjectsEnabled();
        env.setProjectsEnabled(true);

        File sourceRoot = createTemporaryDirectory("srcroot");
        assertTrue("sourceRoot.isDirectory()", sourceRoot.isDirectory());
        File dataroot = createTemporaryDirectory("dataroot");
        assertTrue("dataroot.isDirectory()", dataroot.isDirectory());

        repository1 = new TestRepository();
        repository1.create(HistoryGuru.class.getResourceAsStream(
            "repositories.zip"));

        repository2 = new TestRepository();
        repository2.create(HistoryGuru.class.getResourceAsStream(
            "repositories.zip"));

        // Create symlink #1 underneath source root.
        final String SYMLINK1 = "symlink1";
        File symlink1 = new File(sourceRoot.getCanonicalFile(), SYMLINK1);
        Files.createSymbolicLink(Paths.get(symlink1.getPath()),
            Paths.get(repository1.getSourceRoot()));
        assertTrue("symlink1.exists()", symlink1.exists());

        // Create symlink #2 underneath source root.
        final String SYMLINK2 = "symlink2";
        File symlink2 = new File(sourceRoot.getCanonicalFile(), SYMLINK2);
        Files.createSymbolicLink(Paths.get(symlink2.getPath()),
            Paths.get(repository2.getSourceRoot()));
        assertTrue("symlink2.exists()", symlink2.exists());

        Set<String> allowedSymlinks = new HashSet<>();
        allowedSymlinks.add(symlink1.getAbsolutePath());
        allowedSymlinks.add(symlink2.getAbsolutePath());
        env.setAllowedSymlinks(allowedSymlinks);

        env.setCtags(System.getProperty(
            "org.opensolaris.opengrok.analysis.Ctags", "ctags"));
        env.setSourceRoot(sourceRoot.getPath());
        env.setDataRoot(dataroot.getPath());
        RepositoryFactory.initializeIgnoredNames(env);

        env.setVerbose(false);
        env.setHistoryEnabled(false);
        Indexer.getInstance().prepareIndexer(env, true, true,
                new TreeSet<>(Collections.singletonList("/c")),
                false, false, null, null, new ArrayList<>(), false);

        Project proj1 = env.getProjects().get(SYMLINK1);
        assertNotNull("symlink1 project", proj1);
        proj1.setTabSize(TABSIZE);

        Indexer.getInstance().doIndexerExecution(true, null, null);

        configFile = File.createTempFile("configuration", ".xml");
        env.writeConfiguration(configFile);
        RuntimeEnvironment.getInstance().readConfiguration(new File(
            configFile.getAbsolutePath()));
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        env.setProjectsEnabled(originalProjectsEnabled);
        env.setAllowedSymlinks(new HashSet<>());

        if (repository1 != null) {
            repository1.destroy();
        }
        if (repository2 != null) {
            repository2.destroy();
        }
        if (configFile != null) {
            configFile.delete();
        }

        try {
            TEMP_DIRS.forEach((tempDir) -> {
                try {
                    IOUtils.removeRecursive(tempDir.toPath());
                } catch (IOException e) {
                    // ignore
                }
            });
        } finally {
            TEMP_DIRS.clear();
        }
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testSearch() throws IOException, InvalidTokenOffsetsException {
        SearchEngine instance = new SearchEngine();
        instance.setFreetext("Hello");
        instance.setFile("renamed2.c");
        int noHits = instance.search();
        assertTrue("noHits should be positive", noHits > 0);
        String[] frags = getFirstFragments(instance);
        assertNotNull("getFirstFragments() should return something", frags);
        assertTrue("frags should have one element", frags.length == 1);
        assertNotNull("frags[0] should be defined", frags[0]);

        final String CTX =
            "<a class=\"s\" href=\"/source/symlink1/git/moved2/renamed2.c#16\"><span class=\"l\">16</span> </a><br/>" +
            "<a class=\"s\" href=\"/source/symlink1/git/moved2/renamed2.c#17\"><span class=\"l\">17</span>         printf ( &quot;<b>Hello</b>, world!\\n&quot; );</a><br/>" +
            "<a class=\"s\" href=\"/source/symlink1/git/moved2/renamed2.c#18\"><span class=\"l\">18</span> </a><br/>";
        assertLinesEqual("ContextFormatter output", CTX, frags[0]);
        instance.destroy();
    }

    private String[] getFirstFragments(SearchEngine instance)
            throws IOException, InvalidTokenOffsetsException {

        ContextArgs args = new ContextArgs((short)1, (short)10);

        /*
         * The following `anz' should go unused, but UnifiedHighlighter demands
         * an analyzer "even if in some circumstances it isn't used."
         */
        PlainAnalyzerFactory fac = PlainAnalyzerFactory.DEFAULT_INSTANCE;
        FileAnalyzer anz = fac.getAnalyzer();

        ContextFormatter formatter = new ContextFormatter(args);
        OGKUnifiedHighlighter uhi = new OGKUnifiedHighlighter(env,
            instance.getSearcher(), anz);
        uhi.setBreakIterator(() -> new StrictLineBreakIterator());
        uhi.setFormatter(formatter);
        uhi.setTabSize(TABSIZE);

        ScoreDoc[] docs = instance.scoreDocs();
        for (int i = 0; i < docs.length; ++i) {
            int docid = docs[i].doc;
            Document doc = instance.doc(docid);

            String path = doc.get(QueryBuilder.PATH);
            System.out.println(path);
            formatter.setUrl("/source" + path);

            for (String contextField :
                instance.getQueryBuilder().getContextFields()) {

                Map<String,String[]> res = uhi.highlightFields(
                    new String[]{contextField}, instance.getQueryObject(),
                    new int[] {docid}, new int[] {10});
                String[] frags = res.getOrDefault(contextField, null);
                if (frags != null) {
                    return frags;
                }
            }
        }
        return null;
    }

    private static File createTemporaryDirectory(String name)
            throws IOException {
        File f = FileUtilities.createTemporaryDirectory(name);
        TEMP_DIRS.add(f);
        return f;
    }
}
