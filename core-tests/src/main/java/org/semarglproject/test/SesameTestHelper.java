/**
 * Copyright 2012-2013 the Semargl contributors. See AUTHORS for more details.
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

package org.semarglproject.test;

import info.aduna.io.IOUtil;
import info.aduna.iteration.Iterations;
import org.apache.commons.io.FileUtils;
import org.openrdf.OpenRDFException;
import org.openrdf.model.Model;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LinkedHashModel;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.util.ModelUtil;
import org.openrdf.query.BindingSet;
import org.openrdf.query.BooleanQuery;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.QueryResults;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.query.resultio.helpers.QueryResultCollector;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.rio.ParserConfig;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.helpers.BasicParserSettings;
import org.openrdf.rio.helpers.ContextStatementCollector;
import org.openrdf.rio.helpers.NTriplesParserSettings;
import org.openrdf.rio.helpers.ParseErrorCollector;
import org.openrdf.sail.memory.MemoryStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SesameTestHelper {

    private final String testOutputDir;
    private final Map<String, String> localMirrors;

    public SesameTestHelper(String testOutputDir, Map<String, String> localMirrors) {
        this.testOutputDir = testOutputDir;
        this.localMirrors = localMirrors;
        try {
            File testDir = new File(testOutputDir);
            testDir.mkdirs();
            FileUtils.cleanDirectory(testDir);
        } catch (IOException e) {
            // do nothing
        }
    }

    public static RDFFormat detectFileFormat(String filename) {
        RDFFormat result = Rio.getParserFormatForFileName(filename);
        if (result == null) {
            throw new IllegalArgumentException("Unknown file format");
        }
        return result;
    }

    public InputStream openStreamForResource(String uri)
            throws FileNotFoundException {
        String result = uri;
        for (String remoteUri : localMirrors.keySet()) {
            if (uri.startsWith(remoteUri)) {
                result = uri.replace(remoteUri, localMirrors.get(remoteUri));
            }
        }
        File file = new File(result);
        if (file.exists()) {
            return new FileInputStream(file);
        }
        result = SesameTestHelper.class.getClassLoader().getResource(result).getFile();
        if (result.contains(".jar!/")) {
            try {
                return new URL("jar:" + result).openStream();
            } catch (IOException e) {
                return null;
            }
        }
        return new FileInputStream(result);
    }

    public String getOutputPath(String uri, String ext) {
        String result = uri;
        for (String remoteUri : localMirrors.keySet()) {
            if (uri.startsWith(remoteUri)) {
                result = uri.replace(remoteUri, testOutputDir);
            }
        }
        result = result.substring(0, result.lastIndexOf('.')) + "-out." + ext;
        return result;
    }

    public Model createModelFromFile(String filename, String baseUri) throws IOException {
        Model model = new LinkedHashModel();
        if (filename != null) {
            try {
                RDFParser parser = Rio.createParser(SesameTestHelper.detectFileFormat(filename));
                parser.setRDFHandler(new ContextStatementCollector(model, ValueFactoryImpl.getInstance()));

                ParserConfig config = parser.getParserConfig();
                config.set(BasicParserSettings.PRESERVE_BNODE_IDS, true);
                config.addNonFatalError(BasicParserSettings.VERIFY_DATATYPE_VALUES);
                config.addNonFatalError(BasicParserSettings.VERIFY_LANGUAGE_TAGS);
                // Attempt to normalize known datatypes, including XML Schema
                config.set(BasicParserSettings.NORMALIZE_DATATYPE_VALUES, true);
                // Try not to fail when normalization fails
                config.addNonFatalError(BasicParserSettings.NORMALIZE_DATATYPE_VALUES);

                ParseErrorCollector errors = new ParseErrorCollector();
                parser.setParseErrorListener(errors);

                parser.parse(openStreamForResource(filename), baseUri);

                for(String nextFatalError : errors.getFatalErrors()) {
                    System.err.println("Fatal parse error was ignored : " + filename + " : "+ nextFatalError);
                }
                for(String nextError : errors.getErrors()) {
                    System.err.println("Parse error was ignored : " + filename + " : "+ nextError);
                }
                for(String nextWarning : errors.getWarnings()) {
                    System.err.println("Parse warning was ignored : " + filename + " : "+ nextWarning);
                }
            } catch (OpenRDFException e) {
                System.err.println("Fatal parse error caused failure : " + filename + " : "+ e.getMessage());
                //e.printStackTrace();
                // Avoid returning a partial model if there was a fatal error
                model = new LinkedHashModel();
            }
        }
        return model;
    }

    public <E> List<E> getTestCases(final String manifestUri, String queryStr, final Class<E> template) {
        Repository repository = new SailRepository(new MemoryStore());
        final List<E> testCases = new ArrayList<E>();
        try {
            repository.initialize();
            repository.getConnection().add(openStreamForResource(manifestUri),
                    manifestUri, SesameTestHelper.detectFileFormat(manifestUri));
            TupleQuery query = repository.getConnection().prepareTupleQuery(QueryLanguage.SPARQL, queryStr, manifestUri);
            final TupleQueryResult queryResults = query.evaluate();
            final QueryResultCollector collector = new QueryResultCollector();
            QueryResults.report(queryResults, collector);

            for (BindingSet bindingSet : collector.getBindingSets()) {
                Object testCase = template.newInstance();
                for (String fieldName : bindingSet.getBindingNames()) {
                    try {
                        template.getDeclaredField(fieldName).set(testCase,
                                bindingSet.getBinding(fieldName).getValue().stringValue());
                    } catch (NoSuchFieldException e) {
                    }
                }
                testCases.add((E) testCase);
            }
            return testCases;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean areModelsEqual(String producedModelPath, String expectedModelPath, String baseUri) {
        try {
            Model inputModel = createModelFromFile(producedModelPath, baseUri);
            Model expected = createModelFromFile(expectedModelPath, baseUri);
            return ModelUtil.equals(inputModel, expected);
        } catch (IOException e) {
            return false;
        }
    }

    public String diff(Model model1, Model model2) {
        StringBuilder result = new StringBuilder();
        Model delta = new LinkedHashModel(model1);
        delta.removeAll(model2);
        String[] lines = new String[delta.size()];
        int i = 0;
        for (Statement s : delta) {
            lines[i++] = s.toString();
        }
        Arrays.sort(lines);
        for (String s : lines) {
            result.append("\n").append(s);
        }
        return result.toString();
    }

    public boolean askModel(String resultFilePath, String queryStr, String inputUri, boolean expectedResult) {
        Repository repository = new SailRepository(new MemoryStore());
        RepositoryConnection conn = null;
        try {
            repository.initialize();
            conn = repository.getConnection();
            conn.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_DATATYPE_VALUES);
            conn.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_LANGUAGE_TAGS);
            conn.getParserConfig().addNonFatalError(BasicParserSettings.VERIFY_RELATIVE_URIS);
            conn.add(openStreamForResource(resultFilePath),
                    inputUri, SesameTestHelper.detectFileFormat(resultFilePath));
            BooleanQuery query = repository.getConnection().prepareBooleanQuery(QueryLanguage.SPARQL, queryStr, inputUri);
            boolean result = query.evaluate();

            if (result != expectedResult) {
                System.err.println("Test failed for: " + inputUri);
                System.err.println("Expected [" + expectedResult + "] but found [" + result + "]");
                System.err.println("Query: " + queryStr);
                System.err.println("Statements");
                System.err.println("===============================");
                for (Statement nextStatement : Iterations.asSet(conn.getStatements(null, null, null, true))) {
                    System.err.println(nextStatement);
                }
                System.err.println("===============================");
                InputStream rawResults = openStreamForResource(resultFilePath);
                try {
                    IOUtil.transfer(rawResults, System.err);
                } finally {
                    rawResults.close();
                }
            }

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return !expectedResult;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (RepositoryException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
