/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.javafx.scene.web;

import static javafx.concurrent.Worker.State.SUCCEEDED;
import com.sun.webkit.dom.JSObjectShim;
import com.sun.webkit.dom.NodeImplShim;
import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker.State;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebEngineShim;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;
import test.util.memory.JMemoryBuddy;

public class LeakTest extends TestBase {

    private static final int SLEEP_TIME = 1000;

    @Test public void testOleg() throws InterruptedException{
        final String URL = new File("src/test/resources/test/html/guimark2-vector.html").toURI().toASCIIString();
        final int CYCLE_COUNT = 16;
        final int CYCLE_LENGTH = 5;
        final CountDownLatch latch = new CountDownLatch(CYCLE_COUNT);

        Timeline time = new Timeline();
        time.setCycleCount(CYCLE_LENGTH * CYCLE_COUNT);
        time.getKeyFrames().add(new KeyFrame(Duration.millis(200), new EventHandler<ActionEvent>() {
            int counter = -1;
            @Override public void handle(final ActionEvent e) {
                ++counter;
                if (counter == 0) {
                    WebEngine engine = new WebEngine();
                    engine.load(URL);
                } else if (counter == CYCLE_LENGTH - 1) {
                    counter = -1;
                    latch.countDown();
                }
            }
        }));
        Platform.runLater(time::play);
        latch.await();
    }

    @Test public void testGarbageCollectability() throws InterruptedException {
        final int count = 3;
        WeakReference<?>[] willGC = new WeakReference[count];

        submit(() -> {
            WebView webView = new WebView();
            willGC[0] = new WeakReference<>(webView);
            willGC[1] = new WeakReference<>(webView.getEngine());
            willGC[2] = new WeakReference<>(WebEngineShim.getPage(webView.getEngine()));
        });

        JMemoryBuddy.assertCollectable(willGC);
    }

    private static boolean isAllElementsNull(Reference<?>[] array) {
        for (int j = 0; j < array.length; j++) {
            if (array[j].get() != null) {
                return false;
            }
        }
        return true;
    }

    @Test public void testJSObjectGarbageCollectability() throws InterruptedException {
        final int count = 10000;
        WeakReference<?>[] willGC = new WeakReference[count];

        submit(() -> {
            for (int i = 0; i < count; i++) {
                JSObject tmpJSObject = (JSObject) getEngine().executeScript("new Object()");
                willGC[i] = new WeakReference<>(tmpJSObject);
            }
        });

        JMemoryBuddy.assertCollectable(willGC);
    }

    // JDK-8170938
    @Test public void testJSObjectDisposeCount() throws InterruptedException {
        final int count = 10000;
        Reference<?>[] willGC = new Reference[count];

        submit(() -> {
            for (int i = 0; i < count; i++) {
                JSObject tmpJSObject = (JSObject) getEngine().executeScript("new Object()");
                assertTrue(JSObjectShim.test_getPeerCount() > 0);
                willGC[i] = new WeakReference<>(tmpJSObject);
            }
        });

        Thread.sleep(SLEEP_TIME);

        for (int i = 0; i < 5; i++) {
            System.gc();

            if (isAllElementsNull(willGC)) {
                break;
            }

            Thread.sleep(SLEEP_TIME);
        }

        // Give disposer a chance to run
        Thread.sleep(SLEEP_TIME);
        assertTrue(JSObjectShim.test_getPeerCount() == 0, "All JSObjects are disposed");
    }

    private State getLoadState() {
        return submit(() -> getEngine().getLoadWorker().getState());
    }

    // JDK-8176729
    @Test public void testDOMNodeDisposeCount() throws InterruptedException {
        int count = 7;
        WeakReference<?>[] willGC = new WeakReference[count];
        final String html =
                "<html>\n" +
                        "<head></head>\n" +
                        "<body> <a href=#>hello</a><p> Paragraph </p>\n" +
                        "<div> Div Block </div> <iframe> iframe </iframe> <br> </body>\n" +
                        "</html>";
        loadContent(html);
        WebEngine web = getEngine();

        assertTrue(getLoadState() == SUCCEEDED, "Load task completed successfully");

        System.gc();
        Thread.sleep(SLEEP_TIME);

        // Get the initial NodeImpl hashcount (which is "initialHashCount" below), which
        // can be non-zero if the previous tests leave a strong reference to DOM.
        int initialHashCount = NodeImplShim.test_getHashCount();

        submit(() -> {
            Document doc = web.getDocument();
            assertNotNull(doc, "Document should not be null");

            NodeList tagList = doc.getElementsByTagName("html");
            Element element = (Element) tagList.item(0);
            willGC[0] = new WeakReference<>(element);
            assertEquals(initialHashCount+1, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:html) HashCount");

            tagList = doc.getElementsByTagName("head");
            element = (Element) tagList.item(0);
            willGC[1] = new WeakReference<>(element);
            assertEquals(initialHashCount+2, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:head) HashCount");

            tagList = doc.getElementsByTagName("body");
            element = (Element) tagList.item(0);
            willGC[2] = new WeakReference<>(element);
            assertEquals(initialHashCount+3, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:body) HashCount");

            tagList = doc.getElementsByTagName("p");
            element = (Element) tagList.item(0);
            willGC[3] = new WeakReference<>(element);
            assertEquals(initialHashCount+4, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:p) HashCount");

            tagList = doc.getElementsByTagName("div");
            element = (Element) tagList.item(0);
            willGC[4] = new WeakReference<>(element);
            assertEquals(initialHashCount+5, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:div) HashCount");

            tagList = doc.getElementsByTagName("iframe");
            element = (Element) tagList.item(0);
            willGC[5] = new WeakReference<>(element);
            assertEquals(initialHashCount+6, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:iframe) HashCount");

            tagList = doc.getElementsByTagName("br");
            element = (Element) tagList.item(0);
            willGC[6] = new WeakReference<>(element);
            assertEquals(initialHashCount+7, NodeImplShim.test_getHashCount(), "Expected NodeImpl(tag:br) HashCount");
        });

        JMemoryBuddy.assertCollectable(willGC);
    }
}
