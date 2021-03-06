/*
 * This file is licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sg.com.od.zibiea;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * HTTP service, returns metadata as json, textual content as plain
 * text Can be used by doing a PUT of the file we want to parse Can also be used
 * when the file is available locally at the  Server (using GET)
 *
 * 
 */

@Path("/")
@Component
@Scope("request")
public class ZibieaService {
	private final Log logger = LogFactory.getLog(ZibieaService.class);
	private static final String CONTENT_LENGTH = "Content-Length";
	private static final String FILE_NNAME = "File-Name";
	private static final String RESOURCE_NAME = "resourceName";

	/**
	 * Serves HTTP GET Returns metadata formatted as json or plain text content
	 * of the file. File should be locally accessible for Tika Server using
	 * pathkey JNDI
	 * 
	 * @param filename
	 * @param pathkey
	 *            (JNDI lookup key)
	 * @param opkey
	 *            (can be "text" or "metadata" or "fulldata")
	 * @param httpHeaders
	 * @return
	 * @throws Exception
	 */
	@GET
	@Produces({ MediaType.APPLICATION_JSON })
	@Path("/{opkey}/{pathkey}/{resourceid: .*}")
	public StreamingOutput getMetadata(
			@javax.ws.rs.core.Context javax.ws.rs.core.UriInfo uriInfo,
			@PathParam("opkey") final String opkey,
			@PathParam("pathkey") final String pathkey,
			@PathParam("resourceid") final String resourceId,
			@Context HttpHeaders httpHeaders) throws Exception {

		// get the resource segment, this may have query params
		// we are ok with it as long as we can get something at that location
		String[] segments = uriInfo.getRequestUri().toASCIIString()
				.split("/" + opkey + "/" + pathkey + "/");
		final String filename = segments[segments.length - 1];
		logger.info("resource :" + segments[segments.length - 1]);

		final Detector detector = createDetector(httpHeaders);
		final AutoDetectParser parser = new AutoDetectParser(detector);
		final ParseContext context = new ParseContext();
		context.set(Parser.class, parser);
		final Metadata metadata = new Metadata();
		setMetadataFromHeader(parser, metadata, httpHeaders);

		URL url = null;
		try {
			if (pathkey != null && resourceId != null) {
				String filepath = getFilePath(pathkey) + filename;
				File file = new File(filepath);
				if (file.isFile()) {
					url = file.toURI().toURL();
				} else {
					url = new URL(filepath);
				}
			}
		} catch (MalformedURLException mex) {
			throw new WebApplicationException(Response.Status.NOT_FOUND);
		}
		
		final InputStream is = TikaInputStream.get(url, metadata);

		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException,
					WebApplicationException {

				StringWriter textBuffer = new StringWriter();
				ContentHandler handler = null;
				if (opkey.equalsIgnoreCase("metadata")) {
					handler = new DefaultHandler();
				} else if (opkey.equalsIgnoreCase("text") || opkey.equalsIgnoreCase("fulldata")) {
					handler = new BodyContentHandler(textBuffer);
				}
				try {

					parser.parse(is, handler, metadata, context);

					String contentEncoding = (metadata
							.get(HttpHeaders.CONTENT_ENCODING) == null ? "UTF-8"
							: metadata.get(HttpHeaders.CONTENT_ENCODING));

					logger.info("Content encoding: "+ metadata
							.get(HttpHeaders.CONTENT_ENCODING));
					
					Writer outWriter = getOutputWriter(outputStream,
							contentEncoding);

					//metadata is always gathered
					// munch metadata object it to make json
					String jsonMetadata = JSONHelper
					.metadataToJson(metadata);

					if (opkey.equalsIgnoreCase("metadata")) {
						outWriter.write("{\"metadata\":"+jsonMetadata+"}");
					} else if (opkey.equalsIgnoreCase("text")) {
						// write it out
						outWriter.write("{ \"text\":"
								+ JSONHelper.toJSON(textBuffer.toString())
								+ " }");
					} else if (opkey.equalsIgnoreCase("fulldata")) {
						StringBuilder data = new StringBuilder();
						data.append("{ \"metadata\":"+ jsonMetadata)
							.append(", ")
							.append("\"text\":"
									+ JSONHelper.toJSON(textBuffer.toString())
									+ " }");
						outWriter.write(data.toString());
					}
					outWriter.flush();
				} catch (SAXException e) {
					throw new WebApplicationException(
							Response.Status.INTERNAL_SERVER_ERROR);
				} catch (TikaException e) {
					if (e.getCause() != null
							&& e.getCause() instanceof WebApplicationException) {
						throw (WebApplicationException) e.getCause();
					}

					if (e.getCause() != null
							&& e.getCause() instanceof IllegalStateException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					if (e.getCause() != null
							&& e.getCause() instanceof EncryptedDocumentException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					if (e.getCause() != null
							&& e.getCause() instanceof OldWordFileFormatException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					logger.warn("Text extraction failed", e);

					throw new WebApplicationException(
							Response.Status.INTERNAL_SERVER_ERROR);
				}
			}
		};

	}

	/**
	 * Serves HTTP PUT Returns metadata formatted as json or plain text content
	 * of the file
	 * 
	 * @param filename
	 * @param pathkey
	 *            (JNDI lookup key)
	 * @param opkey
	 *            (can be "text" or "metadata")
	 * @param httpHeaders
	 * @return
	 * @throws Exception
	 */

	@PUT
	@Consumes("*/*")
	@Produces({ MediaType.APPLICATION_JSON })
	@Path("/{opkey}")
	public StreamingOutput getMetadata(final InputStream is,
			@PathParam("opkey") final String opkey,
			@Context HttpHeaders httpHeaders) throws Exception {
		final Detector detector = createDetector(httpHeaders);
		final AutoDetectParser parser = new AutoDetectParser(detector);
		final ParseContext context = new ParseContext();
		context.set(Parser.class, parser);
		final Metadata metadata = new Metadata();
		setMetadataFromHeader(parser, metadata, httpHeaders);

		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException,
					WebApplicationException {

				StringWriter textBuffer = new StringWriter();

				ContentHandler handler = null;
				if (opkey.equalsIgnoreCase("metadata")) {
					handler = new DefaultHandler();
				} else if (opkey.equalsIgnoreCase("text") || opkey.equalsIgnoreCase("fulldata")) {
					handler = new BodyContentHandler(textBuffer);
				}
				try {
					parser.parse(new BufferedInputStream(is), handler,
							metadata, context);
					String contentEncoding = (metadata
							.get(HttpHeaders.CONTENT_TYPE) == null ? "UTF-8"
							: metadata.get(HttpHeaders.CONTENT_TYPE));
					Writer outWriter = getOutputWriter(outputStream,
							contentEncoding);

					//metadata is always gathered
					// munch metadata object it to make json
					String jsonMetadata = JSONHelper
					.metadataToJson(metadata);

					if (opkey.equalsIgnoreCase("metadata")) {
						outWriter.write("{\"metadata\":"+jsonMetadata+"}");
					} else if (opkey.equalsIgnoreCase("text")) {
						// write it out
						outWriter.write("{ \"text\":"
								+ JSONHelper.toJSON(textBuffer.toString())
								+ " }");
					} else if (opkey.equalsIgnoreCase("fulldata")) {
						StringBuilder data = new StringBuilder();
						data.append("{ \"metadata\":"+ jsonMetadata)
							.append(", ")
							.append("\"text\":"
									+ JSONHelper.toJSON(textBuffer.toString())
									+ " }");
						outWriter.write(data.toString());
					}
					outWriter.flush();
				} catch (SAXException e) {
					throw new WebApplicationException(
							Response.Status.INTERNAL_SERVER_ERROR);
				} catch (TikaException e) {
					if (e.getCause() != null
							&& e.getCause() instanceof WebApplicationException) {
						throw (WebApplicationException) e.getCause();
					}

					if (e.getCause() != null
							&& e.getCause() instanceof IllegalStateException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					if (e.getCause() != null
							&& e.getCause() instanceof EncryptedDocumentException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					if (e.getCause() != null
							&& e.getCause() instanceof OldWordFileFormatException) {
						throw new WebApplicationException(Response.status(422)
								.build());
					}

					logger.warn("Text extraction failed", e);

					throw new WebApplicationException(
							Response.Status.INTERNAL_SERVER_ERROR);
				}
			}
		};

	}

	/**
	 * Creates a AutoDetectParser
	 * 
	 * @return
	 */
	public static AutoDetectParser createParser() {
		final AutoDetectParser parser = new AutoDetectParser();

		parser.setFallback(new Parser() {
			public Set<org.apache.tika.mime.MediaType> getSupportedTypes(
					ParseContext parseContext) {
				return parser.getSupportedTypes(parseContext);
			}

			public void parse(InputStream inputStream,
					ContentHandler contentHandler,
					Metadata metadata,
					ParseContext parseContext) {
				throw new WebApplicationException(
						Response.Status.UNSUPPORTED_MEDIA_TYPE);
			}

			public void parse(InputStream inputStream,
					ContentHandler contentHandler,
					Metadata metadata) {
				throw new WebApplicationException(
						Response.Status.UNSUPPORTED_MEDIA_TYPE);
			}
		});

		return parser;
	}

	/**
	 * Set possible metadata from http headers
	 * 
	 * @param parser
	 * @param metadata
	 * @param httpHeaders
	 */
	public void setMetadataFromHeader(AutoDetectParser parser,
			Metadata metadata, HttpHeaders httpHeaders) {
		javax.ws.rs.core.MediaType mediaType = httpHeaders.getMediaType();

		final List<String> fileName = httpHeaders.getRequestHeader(FILE_NNAME), cl = httpHeaders
				.getRequestHeader(CONTENT_LENGTH);
		if (cl != null && !cl.isEmpty())
			metadata.set(CONTENT_LENGTH, cl.get(0));

		if (fileName != null && !fileName.isEmpty())
			metadata.set(RESOURCE_NAME, fileName.get(0));

		if (mediaType != null
				&& !mediaType
						.equals(javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE)) {
			metadata.add(HttpHeaders.CONTENT_TYPE,
					mediaType.toString());

			final Detector detector = parser.getDetector();

			parser.setDetector(new Detector() {
				public org.apache.tika.mime.MediaType detect(
						InputStream inputStream,
						Metadata metadata)
						throws IOException {
					String ct = metadata
							.get(HttpHeaders.CONTENT_TYPE);
					logger.info("Content type " + ct);
					if (ct != null) {
						return org.apache.tika.mime.MediaType.parse(ct);
					} else {
						return detector.detect(inputStream, metadata);
					}
				}
			});
		}
	}

	public Detector createDetector(HttpHeaders httpHeaders) throws IOException,
			TikaException {
		final javax.ws.rs.core.MediaType mediaType = httpHeaders.getMediaType();
		if (mediaType == null
				|| mediaType
						.equals(javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE))
			return (new TikaConfig()).getMimeRepository();
		else
			return new Detector() {

				public org.apache.tika.mime.MediaType detect(
						InputStream inputStream,
						Metadata metadata)
						throws IOException {
					return org.apache.tika.mime.MediaType.parse(mediaType
							.toString());
				}
			};
	}

	/**
	 * Returns a output writer with the given encoding.
	 * 
	 * @see <a
	 *      href="https://issues.apache.org/jira/browse/TIKA-277">TIKA-277</a>
	 * @param output
	 *            output stream
	 * @param encoding
	 *            output encoding, or <code>null</code> for the platform default
	 * @return output writer
	 * @throws UnsupportedEncodingException
	 *             if the given encoding is not supported
	 */
	private static Writer getOutputWriter(OutputStream output, String encoding)
			throws UnsupportedEncodingException {
		if (encoding != null) {
			return new OutputStreamWriter(output, encoding);
		} else if (System.getProperty("os.name").toLowerCase()
				.startsWith("mac os x")) {
			return new OutputStreamWriter(output, "UTF-8");
		} else {
			return new OutputStreamWriter(output);
		}
	}

	/**
	 * Returns a URL for pathkey from JNDI. Used in calls that processes
	 * network-accessible files where you don't want to expose the absolute path
	 * Ensure pathkey is available in JNDI
	 * 
	 * @return filepath
	 */
	private String getFilePath(String pathkey) {
		logger.info("Getting path for "+pathkey);
		String path = "";
		try {
			javax.naming.Context initCtx = new InitialContext();
			path = (String) initCtx.lookup("java:comp/env/"+pathkey);
		} catch (NamingException e) {
			e.printStackTrace();
		}
		return path;
	}

}
