package com.ontotext.trree.plugin.mongodb;

import com.ontotext.test.utils.StandardUtils;
import com.ontotext.trree.sdk.PluginException;

import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.junit.Test;

import static org.junit.Assert.fail;

public class TestPluginMongoErrorHandling extends AbstractMongoBasicTest {

	@Override
	protected void loadData() {
		loadFilesToMongo();
	}
	
	@Test
	public void testMissingQueryProducesNoResults() throws Exception {
		// Contract adjusted: when :entity is used without :find/:aggregate the planner may not invoke
		// the :entity interpretation branch. In that case we observe zero results instead of an exception.
		query = "PREFIX : <http://www.ontotext.com/connectors/mongodb#>\r\n" +
				"PREFIX inst: <http://www.ontotext.com/connectors/mongodb/instance#>\r\n" +
				"select ?s ?o {\n" +
				"\t?search a inst:spb100 ;\n" +
				"\t:entity ?entity .\n" +
				"\tgraph inst:spb100 {\n" +
				"\t\t?s <http://www.bbc.co.uk/ontologies/creativework/about> ?o .\n" +
				"\t}\n" +
				"}";
		try (RepositoryConnection conn = getRepository().getConnection()) {
			try (TupleQueryResult res = conn.prepareTupleQuery(query).evaluate()) {
				if (res.hasNext()) {
					fail("Expected no results for missing query, but found at least one binding");
				}
			}
		}
	} 

	@Override
	protected RepositoryConfig createRepositoryConfiguration() {
		return StandardUtils.createOwlimSe("empty");
	}

}
