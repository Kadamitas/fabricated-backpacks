package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitVisualState;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConduitNativeVerticesTest {
    @Test void nativeFacesKeepTheCollisionBoundsAndOutwardWindingForEveryLaneAndJunction() {
        for (int installed = 1; installed < 8; installed++) for (int faces = 0; faces < 64; faces++) {
            int connections = 0;
            for (Direction face : Direction.values()) if ((faces & (1 << face.ordinal())) != 0)
                connections |= installed << (face.ordinal() * 3);
            var state = new ConduitVisualState(installed, connections, connections, connections, 0, 0);
            for (var part : ConduitGeometry.parts(state)) for (Direction face : Direction.values()) {
                var bounds = part.bounds();
                var vertices = ConduitBlockModel.vertices(bounds, face);
                assertEquals(4, vertices.length);
                for (var vertex : vertices) {
                    assertTrue(vertex.x() >= bounds.minX - 1E-7 && vertex.x() <= bounds.maxX + 1E-7);
                    assertTrue(vertex.y() >= bounds.minY - 1E-7 && vertex.y() <= bounds.maxY + 1E-7);
                    assertTrue(vertex.z() >= bounds.minZ - 1E-7 && vertex.z() <= bounds.maxZ + 1E-7);
                    double coordinate = face.getAxis().choose(vertex.x(), vertex.y(), vertex.z());
                    double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? face.getAxis().choose(bounds.maxX, bounds.maxY, bounds.maxZ)
                            : face.getAxis().choose(bounds.minX, bounds.minY, bounds.minZ);
                    assertEquals(plane, coordinate, 1E-7);
                }
                Vector3f edge1 = new Vector3f(vertices[1]).sub(vertices[0]);
                Vector3f edge2 = new Vector3f(vertices[2]).sub(vertices[0]);
                assertTrue(edge1.cross(edge2).dot(face.getStepX(), face.getStepY(), face.getStepZ()) > 0,
                        "Every visible conduit face winds outwards: " + state + " / " + part + " / " + face);
            }
        }
    }
}
