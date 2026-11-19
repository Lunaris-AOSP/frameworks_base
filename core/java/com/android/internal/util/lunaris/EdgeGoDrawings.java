/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.lunaris;

import android.gesture.Gesture;
import android.gesture.GesturePoint;
import android.gesture.GestureStore;
import android.gesture.GestureStroke;
import android.gesture.Prediction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** @hide */
public final class EdgeGoDrawings {
    private final GestureStore[] stores = {new GestureStore(), new GestureStore(), new GestureStore()};
    private final Map<String, JSONObject> drawings = new HashMap<>();
    private final double confidence;

    public EdgeGoDrawings(List<JSONObject> entries, boolean orientationSensitive, int confidence) {
        this(entries, orientationSensitive, confidence, null);
    }

    public EdgeGoDrawings(List<JSONObject> entries, boolean orientationSensitive, int confidence, String edge) {
        for (int i = 0; i < stores.length; i++) {
            stores[i].setOrientationStyle(orientationSensitive ? GestureStore.ORIENTATION_SENSITIVE : GestureStore.ORIENTATION_INVARIANT);
            stores[i].setSequenceType(i == 0 ? GestureStore.SEQUENCE_SENSITIVE : GestureStore.SEQUENCE_INVARIANT);
        }
        this.confidence = EdgeGoConfig.clamp(confidence, 35, 90) / 25.0;
        for (JSONObject entry : entries) {
            String id = entry.optString("id");
            if (id.isEmpty() || id.length() > 80 || drawings.size() >= 32) continue;
            JSONArray samples = entry.optJSONArray("samples");
            if (samples == null) samples = new JSONArray().put(entry.optJSONArray("points"));
            int count = 0;
            for (int i = 0; i < Math.min(samples.length(), 3); i++) {
                Gesture gesture = decodeSample(samples.opt(i));
                if (gesture != null && gesture.getLength() > 0) {
                    gesture = orient(gesture, entry.optString("edge"), edge);
                    int fingers = gesture.getStrokesCount();
                    if (fingers != entry.optInt("fingers", entry.optInt("fingerCount", fingers))) continue;
                    stores[fingers - 1].addGesture(id, gesture);
                    count++;
                }
            }
            if (count > 0) drawings.put(id, entry);
        }
    }

    public static JSONObject reorientedDrawing(JSONObject drawing, String edge) {
        JSONObject result = EdgeGoConfig.object(drawing.toString());
        String source = drawing.optString("edge");
        if (!source.equals(edge) && validEdge(source) && validEdge(edge)) {
            JSONArray samples = drawing.optJSONArray("samples"), converted = new JSONArray();
            if (samples == null) samples = new JSONArray().put(drawing.optJSONArray("points"));
            for (int i = 0; i < Math.min(samples.length(), 3); i++) {
                Gesture gesture = decodeSample(samples.opt(i));
                if (gesture != null) converted.put(encode(orient(gesture, source, edge)));
            }
            EdgeGoConfig.put(result, "samples", converted);
            result.remove("points");
        }
        EdgeGoConfig.put(result, "edge", edge);
        return result;
    }

    private static Gesture decodeSample(Object sample) {
        return decode(sample instanceof JSONObject ? ((JSONObject) sample).optJSONArray("strokes")
                : sample instanceof JSONArray ? (JSONArray) sample : null);
    }

    private static boolean validEdge(String edge) {
        return "left".equals(edge) || "right".equals(edge) || "top".equals(edge) || "bottom".equals(edge);
    }

    private static Gesture orient(Gesture gesture, String source, String target) {
        if (source.equals(target) || !validEdge(source) || !validEdge(target)) return gesture;
        Gesture transformed = new Gesture();
        for (GestureStroke stroke : gesture.getStrokes()) {
            ArrayList<GesturePoint> points = new ArrayList<>();
            for (int i = 0; i < stroke.points.length; i += 2) {
                float x = stroke.points[i], y = stroke.points[i + 1];
                float inward = source.equals("left") ? x : source.equals("right") ? -x : source.equals("top") ? y : -y;
                float tangent = source.equals("left") || source.equals("right") ? y : x;
                float nextX = target.equals("left") ? inward : target.equals("right") ? -inward : tangent;
                float nextY = target.equals("left") || target.equals("right") ? tangent : target.equals("top") ? inward : -inward;
                points.add(new GesturePoint(nextX, nextY, i / 2 * 16L));
            }
            transformed.addStroke(new GestureStroke(points));
        }
        return transformed;
    }

    public JSONObject match(Gesture gesture) {
        if (gesture == null || gesture.getStrokesCount() == 0 || gesture.getStrokesCount() > 3) return null;
        ArrayList<Prediction> predictions = stores[gesture.getStrokesCount() - 1].recognize(gesture);
        if (predictions.isEmpty() || Double.isNaN(predictions.get(0).score)
                || predictions.get(0).score < confidence) return null;
        if (predictions.size() > 1 && (predictions.get(0).score == predictions.get(1).score
                || predictions.get(0).score < predictions.get(1).score * 1.18)) return null;
        JSONObject result = drawings.get(predictions.get(0).name);
        return result != null && result.optInt("fingers", result.optInt("fingerCount", gesture.getStrokesCount()))
                == gesture.getStrokesCount() ? result : null;
    }

    public static Gesture decode(JSONArray strokes) {
        if (strokes == null || strokes.length() == 0) return null;
        if (strokes.optJSONObject(0) != null && strokes.optJSONObject(0).has("x")) {
            strokes = new JSONArray().put(strokes);
        }
        Gesture gesture = new Gesture();
        for (int i = 0; i < Math.min(strokes.length(), 3); i++) {
            JSONArray points = strokes.optJSONArray(i);
            if (points == null && strokes.optJSONObject(i) != null) {
                points = strokes.optJSONObject(i).optJSONArray("points");
            }
            if (points == null) continue;
            ArrayList<GesturePoint> decoded = new ArrayList<>();
            for (int j = 0; j < Math.min(points.length(), 256); j++) {
                JSONObject point = points.optJSONObject(j);
                if (point == null) continue;
                float x = (float) point.optDouble("x", Double.NaN);
                float y = (float) point.optDouble("y", Double.NaN);
                if (!Float.isFinite(x) || !Float.isFinite(y) || Math.abs(x) > 100000
                        || Math.abs(y) > 100000) continue;
                decoded.add(new GesturePoint(x, y, point.optLong("time", j * 16L)));
            }
            if (decoded.size() >= 2) gesture.addStroke(new GestureStroke(decoded));
        }
        return gesture.getStrokesCount() == 0 ? null : gesture;
    }

    public static JSONArray encode(Gesture gesture) {
        JSONArray strokes = new JSONArray();
        for (GestureStroke stroke : gesture.getStrokes()) {
            JSONArray points = new JSONArray();
            int count = stroke.points.length / 2;
            int step = Math.max(1, (count + 255) / 256);
            for (int i = 0; i < count; i += step) {
                JSONObject point = new JSONObject();
                EdgeGoConfig.put(point, "x", stroke.points[i * 2]);
                EdgeGoConfig.put(point, "y", stroke.points[i * 2 + 1]);
                EdgeGoConfig.put(point, "time", i * 16L);
                points.put(point);
            }
            strokes.put(points);
        }
        return strokes;
    }
}
