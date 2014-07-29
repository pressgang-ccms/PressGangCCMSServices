/*
  Copyright 2011-2014 Red Hat

  This file is part of PressGang CCMS.

  PressGang CCMS is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  PressGang CCMS is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License
  along with PressGang CCMS.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.jboss.pressgang.ccms.services.docbookrenderer.utils;

import java.util.Collections;
import java.util.Map;

import javax.xml.transform.TransformerException;

import org.jboss.pressgang.ccms.rest.v1.entities.RESTBlobConstantV1;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InternalProcessingException;
import org.jboss.pressgang.ccms.rest.v1.exceptions.InvalidParameterException;
import org.jboss.pressgang.ccms.rest.v1.jaxrsinterfaces.RESTInterfaceV1;
import org.jboss.pressgang.ccms.utils.common.NotificationUtilities;
import org.jboss.pressgang.ccms.utils.common.XSLTUtilities;
import org.jboss.pressgang.ccms.utils.common.ZipUtilities;
import org.jboss.resteasy.client.ProxyFactory;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

/**
 * This class provides a way to convert docbook to HTML.
 */
public class XMLRenderer
{
	/**
	 * This is the ID of the BlobConstant record that holds the docbook XSL ZIP
	 * archive downloaded from
	 * http://sourceforge.net/projects/docbook/files/docbook-xsl/
	 */
	private static final Integer DOCBOOK_ZIP_ID = 6;
	/**
	 * This is the ID of the BlobConstant record that holds the DocBook DTD ZIP
	 * archive downloaded from
	 * http://www.docbook.org/xml/4.5/docbook-xml-4.5.zip
	 */
	private static final Integer DOCBOOK_DTD_ZIP_FILE = 8;
	/**
	 * This is the URL of the xsl files imported by the html.xsl file below. We
	 * use this as the system id when using a URIResolver to allow us to track
	 * the context in which files are imported.
	 */
	private static final String DOCBOOK_XSL_URL = "http://docbook.sourceforge.net/release/xsl/current/";

	private static final String DOCBOOK_XSL_SYSTEMID = "http://docbook.sourceforge.net/release/xsl/current/xhtml/docbook.xsl";

	private static final String DOCBOOK_DTD_SYSTEMID = "http://www.oasis-open.org/docbook/xml/4.5/";

	private static Map<String, byte[]> docbookFiles = null;

	private synchronized static void initialize(final String skynetServer) throws InvalidParameterException, InternalProcessingException
	{
		if (docbookFiles == null)
		{
			NotificationUtilities.dumpMessageToStdOut("Initializing XMLRenderer");

			RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
			final RESTInterfaceV1 client = ProxyFactory.create(RESTInterfaceV1.class, skynetServer);

			final RESTBlobConstantV1 docbookZip = client.getJSONBlobConstant(DOCBOOK_ZIP_ID, "");
			final RESTBlobConstantV1 docbookDTDZip = client.getJSONBlobConstant(DOCBOOK_DTD_ZIP_FILE, "");

			if (docbookZip != null)
			{
				/* load the xsl files from the docbook xsl package */
				docbookFiles = ZipUtilities.mapZipFile(docbookZip.getValue(), DOCBOOK_XSL_URL, "docbook-xsl-1.76.1/");
				ZipUtilities.mapZipFile(docbookDTDZip.getValue(), docbookFiles, DOCBOOK_DTD_SYSTEMID, "");

				/* make the collection read only for the threads */
				docbookFiles = Collections.unmodifiableMap(docbookFiles);
			}

		}
	}

	public static String transformDocbook(final String xml, final String skynetServer) throws InvalidParameterException, InternalProcessingException, TransformerException
	{
		initialize(skynetServer);

		if (xml != null && docbookFiles != null && docbookFiles.containsKey(DOCBOOK_XSL_SYSTEMID))
			return XSLTUtilities.transformXML(xml, new String(docbookFiles.get(DOCBOOK_XSL_SYSTEMID)), DOCBOOK_XSL_SYSTEMID, docbookFiles, null);

		return null;
	}
}
