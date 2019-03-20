/* This file is part of VoltDB.
 * Copyright (C) 2008-2019 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.export;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.voltdb.BackendTarget;
import org.voltdb.client.Client;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.export.TestExportBaseSocketExport.ServerListener;
import org.voltdb.regressionsuites.LocalCluster;
import org.voltdb.utils.VoltFile;

public class TestExportAlterStream extends TestExportLocalClusterBase
{
    private LocalCluster m_cluster;

    private static int KFACTOR = 1;
    private static final String SCHEMA =
            "CREATE STREAM t "
            + "PARTITION ON COLUMN a "
            + "EXPORT TO TARGET t ("
            + "     a integer not null, "
            + "     b integer not null"
            + ");";

    static void resetDir() throws IOException {
        File f = new File("/tmp/" + System.getProperty("user.name"));
         VoltFile.recursivelyDelete(f);
         f.mkdirs();
    }

    @Before
    public void setUp() throws Exception
    {
        resetDir();
        VoltFile.resetSubrootForThisProcess();

        VoltProjectBuilder builder = null;
        builder = new VoltProjectBuilder();
        builder.addLiteralSchema(SCHEMA);
        builder.setUseDDLSchema(true);
        builder.setPartitionDetectionEnabled(true);
        builder.setDeadHostTimeout(30);
        // Each stream needs an exporter configuration
        String streamName = "t";
        builder.addExport(true /* enabled */,
                         "custom", /* custom exporter:  org.voltdb.exportclient.SocketExporter*/
                         createSocketExportProperties(streamName, false /* is replicated stream? */),
                         streamName);
        // Start socket exporter client
        startListener();

        // A test hack to use socket exporter
        Map<String, String> additionalEnv = new HashMap<String, String>();
        System.setProperty(ExportDataProcessor.EXPORT_TO_TYPE, "org.voltdb.exportclient.SocketExporter");
        additionalEnv.put(ExportDataProcessor.EXPORT_TO_TYPE, "org.voltdb.exportclient.SocketExporter");

        m_cluster = new LocalCluster("testFlushExportBuffer.jar", 3, 2, KFACTOR, BackendTarget.NATIVE_EE_JNI);
        m_cluster.setNewCli(true);
        m_cluster.setHasLocalServer(true);
        m_cluster.overrideAnyRequestForValgrind();
        // Config custom socket exporter
        boolean success = m_cluster.compile(builder);
        assertTrue(success);
        m_cluster.startUp(true);

        // TODO: verifier should be created based on socket exporter settings
        m_verifier = new ExportTestExpectedData(m_serverSockets, false /*is replicated stream? */, true, KFACTOR + 1);
    }

    @After
    public void tearDown() throws Exception {
        System.out.println("Shutting down client and server");
        for (Entry<String, ServerListener> entry : m_serverSockets.entrySet()) {
            ServerListener serverSocket = entry.getValue();
            if (serverSocket != null) {
                serverSocket.closeClient();
                serverSocket.close();
            }
        }
        m_cluster.shutDown();
    }

    @Test
    public void testAlterStreamAddColumn() throws Exception {
        Client client = getClient(m_cluster);

        for (int i = 0; i < 100; i++) {
            //add data to stream table
            Object[] data = new Object[3];
            data[0] = 1;
            data[1] = i;
            data[2] = 1;
            m_verifier.addRow(client, "t", i, data);
            client.callProcedure("@AdHoc", "insert into t values(" + i + ", 1)");
        }

        client.drain();
        TestExportBaseSocketExport.waitForStreamedTargetAllocatedMemoryZero(client);
        m_verifier.verifyRows();
    }
}
