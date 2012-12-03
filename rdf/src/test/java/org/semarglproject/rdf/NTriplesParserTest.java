/*
 * Copyright 2012 Lev Khomich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.semarglproject.rdf;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import org.semarglproject.JenaSinkWrapper;
import org.semarglproject.SinkWrapper;
import org.semarglproject.TestUtils;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static org.testng.AssertJUnit.assertTrue;

public final class NTriplesParserTest {

    private static final String FAILURES_DIR = "target/n3_failed";

    private static final String TESTSUITE_DIR = "src/test/resources/w3c";
    private static final String RDF_TEST_SUITE_ROOT = "http://www.w3.org/2000/10/rdf-tests/rdfcore";

    @BeforeClass
    public static void cleanTargetDir() {
        File failuresDir = new File(FAILURES_DIR);
        TestUtils.deleteDir(failuresDir);
        failuresDir.mkdirs();
    }

    @DataProvider
    public Object[][] getTestFiles() {
        List<String> result = new ArrayList<String>();
        String queryStr = null;
        String manifestUri = RDF_TEST_SUITE_ROOT + "/Manifest.rdf";
        Model graph = ModelFactory.createDefaultModel();

        try {
            graph.read(new FileInputStream("src/test/resources/w3c/Manifest.rdf"), manifestUri, "RDF/XML");
            queryStr = TestUtils.readFileToString(new File(
                    "src/test/resources/fetch_ntriples_tests.sparql"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        Query query = QueryFactory.create(queryStr, manifestUri);
        QueryExecution qe = QueryExecutionFactory.create(query, graph);
        ResultSet rs = qe.execSelect();
        while (rs.hasNext()) {
            QuerySolution qs = rs.next();
            String testName = qs.getResource("ntriples_file").getURI();
            result.add(testName);
        }
        qe.close();
        int i = 0;
        Object[][] res = new Object[result.size()][];
        for (String str : result) {
            res[i++] = new Object[] { str };
        }
        return res;
    }

    @Test(dataProvider = "getTestFiles")
    public void NTriplesTestsJena(String caseName) throws Exception {
        runTestBundle(caseName, new JenaSinkWrapper());
    }

    void runTestBundle(String caseName, SinkWrapper wrapper) throws FileNotFoundException {
        File docFile = new File(caseName.replace(RDF_TEST_SUITE_ROOT, TESTSUITE_DIR));

        File output = new File(FAILURES_DIR, caseName.substring(caseName.lastIndexOf('/') + 1));
        Model outputModel = ModelFactory.createDefaultModel();
        Model resultModel = ModelFactory.createDefaultModel();

        boolean invalidInput = false;
        FileInputStream inputStream = new FileInputStream(docFile);
        try {
            resultModel.read(inputStream, caseName, "N-TRIPLE");
        } catch (Exception e) {
            invalidInput = true;
        } finally {
            TestUtils.closeQuietly(inputStream);
        }

        try {
            extract(docFile, caseName, output, wrapper);
        } catch (ParseException e) {
            if (invalidInput) {
                output.delete();
                return;
            }
        }

        inputStream = new FileInputStream(output);
        try {
            outputModel.read(inputStream, caseName, "TURTLE");
        } finally {
            TestUtils.closeQuietly(inputStream);
        }

        boolean success = outputModel.isIsomorphicWith(resultModel);
        if (success) {
            output.delete();
        }
        assertTrue(success);
    }

    private void extract(File inputFile, String baseUri, File outputFile, SinkWrapper wrapper)
            throws ParseException, FileNotFoundException {
        wrapper.reset();

        DataProcessor<Reader> dp = new CharSource()
                .streamingTo(new NTriplesParser()
                    .streamingTo(wrapper.getSink())).build();
        FileReader reader = new FileReader(inputFile);
        try {
            dp.process(reader, baseUri);
        } finally {
            TestUtils.closeQuietly(reader);
        }
        if (outputFile == null) {
            return;
        }
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        try {
            wrapper.dumpToStream(outputStream);
        } finally {
            TestUtils.closeQuietly(outputStream);
        }
    }
}