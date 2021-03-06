/**
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
package uk.nhs.ciao.fhir;

import org.apache.camel.Exchange;
import org.apache.camel.builder.xml.Namespaces;

import uk.nhs.ciao.CIPRoutes;

/**
 * Routes for our FHIR endpoint
 * 
 * Note: I think you will need the camel-jetty component in apache service mix:
 * features:install camel-jetty
 *
 * 
 * Interactions defined in the FHIR ReST API:
 * 
 * read			-> GET [base]/[type]/[id] {?_format=[mime-type]}
 * vread		-> GET [base]/[type]/[id]/_history/[vid] {?_format=[mime-type]}
 * update		-> PUT [base]/[type]/[id] {?_format=[mime-type]}
 * delete		-> DELETE [base]/[type]/[id]
 * create		-> POST [base]/[type] {?_format=[mime-type]}
 * search		-> GET [base]?[parameters] {&_format=[mime-type]}
 * validate		-> POST [base]/[type]/_validate{/[id]}
 * conformance	-> GET [base]/metadata {?_format=[mime-type]}
 *				   OPTIONS [base] {?_format=[mime-type]}
 * transaction	-> POST [base] {?_format=[mime-type]}
 * history		-> GET [base]/[type]/[id]/_history{?[parameters]&_format=[mime-type]}
 * 
 * Tag operations 
 * via header	-> Category: [Tag Term]; scheme="[Tag Scheme]"; label="[Tag label]"(, ...)
 * Get tags		-> GET [base]/_tags
 * 
 */
public class FHIRRoutes extends CIPRoutes {

	@Override
    public void configure() {
    	
    	Namespaces ns = new Namespaces("SOAP-ENV", "http://schemas.xmlsoap.org/soap/envelope/");
    	ns.add("hl7", "urn:hl7-org:v3");
    	
    	// Put all new http requests for the patient resource onto a JMS queue
    	from("jetty:http://0.0.0.0:8080/fhir/Patient?traceEnabled=true").routeId("fhir-patient-http")
    		.log("CIP Name: {{cip.name}}")
    		.to("jms:ciao-fhir");
    	
    	// Read each request from the queue and create the Spine SOAP message
    	from("jms:ciao-fhir").routeId("fhir-patient-requesthandler")
    		.choice()
            	.when(header(Exchange.HTTP_METHOD).isEqualTo("GET"))
            		.log("GET Request")
            		
            		// The format that the requester wants can be specified in the
            		// request, so we can store that as a property in the Exchange
            		// for use later. This can be specified in the Accept header or
            		// using a _format query parameter
            		.choice()
	            		.when(header("_format").isNotNull())
	            			.setProperty("fhirResourceFormat").simple("${header._format}")
	            			.to("direct:generateSpineRequest")
	            		.when(header("Accept").contains("fhir"))
	            			.setProperty("fhirResourceFormat").simple("${header.Accept}")
	            			.to("direct:generateSpineRequest")
	            		.otherwise()
	            			.to("direct:generateSpineRequest")
	            		.endChoice()
            	.otherwise()
            		// We don't support POST requests, so return a suitable error.
            		
            		.log("POST Request")
            		.beanRef("patientPostProcessor");
    	
    	// Generate Spine Request
    	from("direct:generateSpineRequest").routeId("fhir-patient-requestGenerator")
    		// Pass the query parameters into a java class to
    		// create the SOAP request content
    		.beanRef("payloadBuilder",
	    				"buildSimpleTrace(${header.family},"
			    				+ "${header.gender},"
			    				+ "${header.birthdate},"
			    				+ "{{ASID}},"
			    				+ "{{PDSASID}},"
			    				+ "{{PDSURL}},"
			    				+ "{{SOAPFromAddress}})")
    		.to("direct:spineSender");
    		
    	// Send to Spine
    	from("direct:spineSender").routeId("fhir-patient-spineSender")
    		// First, log the outbound message
    		.wireTap("jms:ciao-spineRequestAudit")
    		// Remove any request headers that came in from the client's request
    		.removeHeaders("*")
    		// Set the SOAPAction header and specfy the URL for the Spine request
    		.setHeader("SOAPaction", simple("urn:nhs:names:services:pdsquery/QUPA_IN000005UK01"))
    		.setHeader(Exchange.HTTP_URI, simple("{{PDSURL}}"))
    		// Send the message, using the configured security context (to
    		// handle the TLS MA connection
    		.to("http4://dummyurl"
    						+ "?sslContextParametersRef=spineSSLContextParameters")
    		// Log the message that comes back from Spine
    		.wireTap("jms:ciao-spineResponseAudit")
    		.to("direct:responseProcessor");
    	
    	// Parse the response
    	from("direct:responseProcessor")
    		.streamCaching()
    		// Route based on type of response from Spine
    		.choice()
    			// A response code that doesn't start with 2 can be treated as an error
    			.when(header(Exchange.HTTP_RESPONSE_CODE).not().startsWith("2")).beanRef("HTTPErrorProcessor")
    		.otherwise()
    			/*.convertBodyTo(org.w3c.dom.Document.class)
    			.choice()
	    			.when().xpath("/SOAP-ENV:Envelope/SOAP-ENV:Body/hl7:traceQueryResponse/hl7:QUQI_IN010000UK14", ns).beanRef("QueryActFailedProcessor")
	    			.when().xpath("/SOAP-ENV:Envelope/SOAP-ENV:Body/SOAP-ENV:Fault", ns).beanRef("SOAPFaultProcessor")
	    			.otherwise()*/
    					.log("FHIR Format requested was: ${property.fhirResourceFormat}")
    					.beanRef("patientResponseProcessor");
    	
    	// Log spine messages
    	from("jms:ciao-spineRequestAudit")
    		.to("file:log/spine?fileName=${date:now:yyyy-MM-dd}-${exchangeId}-Request.xml");
    	from("jms:ciao-spineResponseAudit")
    		.to("file:log/spine?fileName=${date:now:yyyy-MM-dd}-${exchangeId}-Response.xml");
    	
    	// Conformance Profile
    	from("jetty:http://0.0.0.0:8080/fhir/metadata?traceEnabled=true").routeId("fhir-conformance")
    		.to("direct:conformance");
    	
    	from("jetty:http://0.0.0.0:8080/fhir?traceEnabled=true").routeId("fhir-conformance-options")
			.choice()
            	.when(header(Exchange.HTTP_METHOD).isEqualTo("OPTIONS"))
			    	.to("direct:conformance");
    	
    	from("direct:conformance")
			.beanRef("conformanceProcessor");
    	
    	super.configure();
    }
}