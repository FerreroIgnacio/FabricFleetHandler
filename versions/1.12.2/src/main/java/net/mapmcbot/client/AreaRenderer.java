package net.mapmcbot.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.GlStateManager;
import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotVis;
import net.mapmcbot.mixin.WorldRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BlockBreakingInfo;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.opengl.GL11;

/**
 * Each area as a coloured box in the world, plus the block a click would pick while picking, and
 * the path each walking bot is on with its target, and the chests of each grab going on: the one
 * its bot is going to as a box, the ones it may look in after as an outline, in the bot's colour; and
 * the blocks each quarrify chooses from, green; and the blocks clicked in chat messages, red; and
 * what each bot's wsr primitive shows (see BotVis), in its colour, red once failed.
 *
 * Drawn at the end of entity rendering, where the camera transform is set with the camera at the
 * origin: boxes are shifted by the interpolated camera position, the raw one makes them jitter.
 */
public final class AreaRenderer {
	/** Beyond this a box is a couple of pixels and not worth drawing. */
	private static final double MAX_RENDER_DISTANCE_SQ = 512.0 * 512.0;

	/** Pulled in a hair so the outline does not z-fight with the faces of the blocks. */
	private static final double EXPANSION = -0.01;
	/** A block's number 8 px of text half its side tall. */
	private static final float NUMBER_SCALE = 1.0F / 16;
	private static final int EDGE_ALPHA = 0xFF;
	private static final int FILL_ALPHA = 0x20;
	/** The face aimed at and the dig's fill, over the box's own. */
	private static final int FACE_ALPHA = 0x60;
	/** The region the aim's miss picks from (see drawRegion). */
	private static final int REGION_ALPHA = 0xD8;
	/** A failed wsr primitive's, and an aim the head turned off. */
	private static final int FAILED = 0xFF0000;
	/** Half the side of the box marking a point (an aim, the feet). */
	private static final double POINT = 0.05;
	/** The eyes over the feet, standing and crouched, as 1.12 has them (see actions.js SNEAK_EYE_HEIGHT). */
	private static final double EYE_HEIGHT = 1.62;
	private static final double SNEAK_EYE_HEIGHT = 1.54;
	/** The stages of a block's cracks, as the server sends them: 0 to 9. */
	private static final int BREAKING_STAGES = 10;
	/** A label's lines, the widest at most this much of the block's side. */
	private static final double LABEL_WIDTH = 0.9;

	/**
	 * What is drawn, cycled with its key: 1 everything, 2 no quarrify depths, 3 no quarrify eligible,
	 * 4 nothing but the block a pick would pick; then each bot in turn, by name (see bot), and back to 1.
	 */
	private static int mode = 1;
	/** In a bot's mode, the bot: of the bots' paths, grabs and wsr primitives, its own; no areas, no quarrify's. */
	private static String bot;

	private AreaRenderer() {
	}

	public static void nextMode() {
		if (bot == null && mode < 4) {
			mode++;
			return;
		}

		// By name, not by place in the list: bots come and go in a session.
		final BotRegistry bots = MapMcBotClient.botsOrNull();
		String next = null;

		if (bots != null) {
			for (String name : bots.getBots()) {
				if ((bot == null || name.compareTo(bot) > 0) && (next == null || name.compareTo(next) < 0)) {
					next = name;
				}
			}
		}

		bot = next;
		mode = 1;
	}

	/** The bot of the mode, null in 1 to 4; one removed from the session leaves the mode at 1. */
	public static String selected() {
		final BotRegistry bots = MapMcBotClient.botsOrNull();

		if (bot != null && (bots == null || !bots.getBots().contains(bot))) {
			bot = null;
		}

		return bot;
	}

	public static void render(float tickDelta) {
		final MinecraftClient client = MinecraftClient.getInstance();

		if (client.world == null || client.player == null) {
			return;
		}

		final boolean all = mode != 4;
		final String selected = selected();
		final AreaStore store = all && selected == null ? MapMcBotClient.areas() : null;
		final BlockPos target = AreaPick.isActive() ? AreaPick.target(client) : TargetPick.isActive() ? TargetPick.target(client) : null;
		final Box entityTarget = TargetPick.isActive() ? TargetPick.entityBox(client) : null;
		final BotRegistry bots = MapMcBotClient.botsOrNull();
		final Map<String, int[]> paths = bots == null || !all ? Collections.<String, int[]>emptyMap() : bots.getPaths();
		final Map<String, BotVis> visuals = bots == null || !all ? Collections.<String, BotVis>emptyMap() : bots.getVisuals();
		final Grab grab = MapMcBotClient.grabOrNull();
		final List<Grab.Highlight> chests = grab == null || !all ? Collections.<Grab.Highlight>emptyList() : grab.highlights();

		final List<BlockPos> highlighted = all ? BlockLinks.highlighted() : Collections.<BlockPos>emptyList();
		final Quarrify quarrify = MapMcBotClient.quarrifyOrNull();
		final List<int[]> eligible = quarrify == null || mode >= 3 || selected != null ? Collections.<int[]>emptyList() : quarrify.eligible();
		final List<int[]> depths = quarrify == null || mode == 2 || mode == 4 || selected != null ? Collections.<int[]>emptyList() : quarrify.depths();

		if ((store == null || store.getAreas().isEmpty()) && target == null && entityTarget == null && paths.isEmpty() && visuals.isEmpty() && chests.isEmpty() && highlighted.isEmpty() && eligible.isEmpty() && depths.isEmpty()) {
			return;
		}

		final Entity camera = client.getCameraEntity() == null ? client.player : client.getCameraEntity();
		final double camX = camera.prevTickX + (camera.x - camera.prevTickX) * tickDelta;
		final double camY = camera.prevTickY + (camera.y - camera.prevTickY) * tickDelta;
		final double camZ = camera.prevTickZ + (camera.z - camera.prevTickZ) * tickDelta;

		GlStateManager.pushMatrix();
		GlStateManager.enableBlend();
		GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
				GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableTexture();
		GlStateManager.disableCull();

		// No depth writes so one box's fill does not hide another; the depth test stays on.
		GlStateManager.depthMask(false);
		GL11.glLineWidth(2.0F);

		if (store != null) {
			final List<Area> areas = store.getAreas();

			for (int i = 0; i < areas.size(); i++) {
				final Area area = areas.get(i);

				if (!isTooFar(area, camX, camY, camZ)) {
					drawBox(area.getMinX(), area.getMinY(), area.getMinZ(),
							area.getMaxX() + 1, area.getMaxY() + 1, area.getMaxZ() + 1,
							area.getColor(), camX, camY, camZ);
				}
			}
		}

		if (target != null) {
			drawBox(target.getX(), target.getY(), target.getZ(),
					target.getX() + 1, target.getY() + 1, target.getZ() + 1, 0xFFFFFF, camX, camY, camZ);
		}

		if (entityTarget != null) {
			drawBox(entityTarget.minX, entityTarget.minY, entityTarget.minZ, entityTarget.maxX, entityTarget.maxY, entityTarget.maxZ, 0xFFFFFF, camX, camY, camZ);
		}

		// TODO(world): a bot may be on another server or dimension than this client's (a relay's, or a local
		// fleet's), and its path is drawn here all the same. Needs the bot's world with its PATH, and only the paths of this world drawn.
		for (Map.Entry<String, int[]> entry : paths.entrySet()) {
			if (selected != null && !entry.getKey().equals(selected)) {
				continue;
			}

			final int[] path = entry.getValue();
			final int rgb = MapMcBotClient.colorOf(entry.getKey()).getRgb();
			drawBox(path[0], path[1], path[2], path[0] + 1, path[1] + 1, path[2] + 1, rgb, camX, camY, camZ);
			drawPath(path, rgb, camX, camY, camZ);
		}

		for (Map.Entry<String, BotVis> entry : visuals.entrySet()) {
			if (selected != null && !entry.getKey().equals(selected)) {
				continue;
			}

			drawVis(client, entry.getValue(), MapMcBotClient.colorOf(entry.getKey()).getRgb(), camX, camY, camZ);
		}

		for (Grab.Highlight chest : chests) {
			if (selected != null && !chest.bot.equals(selected)) {
				continue;
			}

			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

			for (int[] block : chest.chest.getBlocks()) {
				minX = Math.min(minX, block[0]);
				minY = Math.min(minY, block[1]);
				minZ = Math.min(minZ, block[2]);
				maxX = Math.max(maxX, block[0]);
				maxY = Math.max(maxY, block[1]);
				maxZ = Math.max(maxZ, block[2]);
			}

			final int rgb = MapMcBotClient.colorOf(chest.bot).getRgb();

			if (chest.current) {
				drawBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, rgb, camX, camY, camZ);
			} else {
				drawOutline(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1, rgb, camX, camY, camZ);
			}
		}

		// The blocks of the active layer each quarrify chooses from (see Quarrify.pick), as one shape.
		if (!eligible.isEmpty()) {
			drawCells(eligible, 0x00FF00, camX, camY, camZ);
		}

		// Each block of the active layer's depth (see Quarrify.depth), on its top and sides.
		if (!depths.isEmpty()) {
			GlStateManager.enableTexture();

			for (int[] block : depths) {
				drawNumber(client, block, camX, camY, camZ);
			}

			GlStateManager.disableTexture();
		}

		// The blocks clicked in chat (see BlockLinks), over everything.
		GlStateManager.disableDepthTest();

		for (BlockPos block : highlighted) {
			drawBox(block.getX(), block.getY(), block.getZ(), block.getX() + 1, block.getY() + 1, block.getZ() + 1, 0xFF0000, camX, camY, camZ);
		}

		GlStateManager.enableDepthTest();

		// Line width is global state the rest of the frame would inherit.
		GL11.glLineWidth(1.0F);
		GlStateManager.depthMask(true);
		GlStateManager.enableCull();
		GlStateManager.enableTexture();
		GlStateManager.disableBlend();
		GlStateManager.popMatrix();
	}

	/** The block's number {x, y, z, number} written on its top and its 4 sides, just off them. */
	private static void drawNumber(MinecraftClient client, int[] block, double camX, double camY, double camZ) {
		drawLabel(client, block, new String[] {String.valueOf(block[3])}, 0xFFFFFF, camX, camY, camZ);
	}

	/**
	 * The lines written on the block's top and its 4 sides, just off them, as its depth is: NUMBER_SCALE
	 * tall, smaller when the widest is wider than LABEL_WIDTH of the side.
	 */
	private static void drawLabel(MinecraftClient client, int[] block, String[] lines, int rgb, double camX, double camY, double camZ) {
		int width = 0;

		for (String line : lines) {
			width = Math.max(width, client.textRenderer.getStringWidth(line));
		}

		final float scale = (float) Math.min(NUMBER_SCALE, LABEL_WIDTH / width);
		final int height = lines.length * client.textRenderer.fontHeight;

		for (int face = 0; face < 5; face++) {
			GlStateManager.pushMatrix();
			GlStateManager.translate(block[0] + 0.5 - camX, block[1] + 0.5 - camY, block[2] + 0.5 - camZ);

			if (face == 4) {
				GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
			} else {
				GlStateManager.rotate(face * 90.0F, 0.0F, 1.0F, 0.0F);
			}

			GlStateManager.translate(0.0, 0.0, 0.5 - EXPANSION);
			GlStateManager.scale(scale, -scale, scale);

			for (int i = 0; i < lines.length; i++) {
				client.textRenderer.draw(lines[i], -client.textRenderer.getStringWidth(lines[i]) / 2, -height / 2 + i * client.textRenderer.fontHeight, rgb);
			}

			GlStateManager.popMatrix();
		}
	}

	/**
	 * A wsr primitive (see BotVis), in rgb, red once failed: its target as a box (a place's as its
	 * outline, the block to come), filled from the bottom as far as its dig got, and its phase on it;
	 * the face aimed at, the point and the line from the eyes to it (red when the head turned off it),
	 * and greyed the point the miss goes off from and how far it goes each side (see BotVis#aimBox);
	 * the spot to stand in and its feet, with the eyes over them, and the walk there, darker; end, and
	 * partial, darker; the stretch broken or placed walking on, thick, and where it took over on it.
	 */
	private static void drawVis(MinecraftClient client, BotVis vis, int color, double camX, double camY, double camZ) {
		final int rgb = vis.failed() ? FAILED : color;
		final int[] t = vis.target;

		if (vis.place) {
			drawOutline(t[0], t[1], t[2], t[0] + 1, t[1] + 1, t[2] + 1, rgb, camX, camY, camZ);
		} else {
			drawBox(t[0], t[1], t[2], t[0] + 1, t[1] + 1, t[2] + 1, rgb, camX, camY, camZ);
		}

		final int stage = vis.place ? -1 : breakingStage(client, t);

		if (stage >= 0) {
			drawFill(t[0], t[1], t[2], t[0] + 1, t[1] + (stage + 1) / (double) BREAKING_STAGES, t[2] + 1, rgb, FACE_ALPHA, camX, camY, camZ);
		}

		if (vis.eyes != null) {
			final int aim = vis.missed ? FAILED : rgb;
			drawFace(vis.aimBlock, vis.aimFace, aim, camX, camY, camZ);
			drawRegion(vis.aimBox, vis.aimFace, grey(aim), camX, camY, camZ);
			drawPoint(vis.aimRay, grey(aim), camX, camY, camZ);
			drawPoint(vis.aimPoint, aim, camX, camY, camZ);
			drawLine(vis.eyes, vis.aimPoint, aim, camX, camY, camZ);
		}

		if (vis.spot != null) {
			drawOutline(vis.spot[0], vis.spot[1], vis.spot[2], vis.spot[0] + 1, vis.spot[1] + 1, vis.spot[2] + 1, rgb, camX, camY, camZ);
		}

		if (vis.feet != null) {
			drawPoint(vis.feet, rgb, camX, camY, camZ);
			drawLine(vis.feet, new double[] {vis.feet[0], vis.feet[1] + (vis.crouch ? SNEAK_EYE_HEIGHT : EYE_HEIGHT), vis.feet[2]}, rgb, camX, camY, camZ);
		}

		drawBlocks(vis.walk, dark(rgb), camX, camY, camZ);

		if (vis.end != null) {
			drawOutline(vis.end[0], vis.end[1], vis.end[2], vis.end[0] + 1, vis.end[1] + 1, vis.end[2] + 1, rgb, camX, camY, camZ);
		}

		if (vis.partial != null) {
			drawOutline(vis.partial[0], vis.partial[1], vis.partial[2], vis.partial[0] + 1, vis.partial[1] + 1, vis.partial[2] + 1, dark(rgb), camX, camY, camZ);
		}

		GL11.glLineWidth(4.0F);
		drawBlocks(vis.run, rgb, camX, camY, camZ);
		GL11.glLineWidth(2.0F);

		if (vis.takeover != null) {
			drawPoint(new double[] {vis.takeover[0] + 0.5, vis.takeover[1] + 0.1, vis.takeover[2] + 0.5}, rgb, camX, camY, camZ);
		}

		final String[] label = stage >= 0 ? new String[] {vis.phase, (stage + 1) + "/" + BREAKING_STAGES} : new String[] {vis.phase};
		GlStateManager.enableTexture();
		drawLabel(client, t, label, dark(rgb), camX, camY, camZ);
		GlStateManager.disableTexture();
	}

	/** The stage of the cracks the server shows on the block, 0 to 9; -1 while nobody breaks it. */
	private static int breakingStage(MinecraftClient client, int[] block) {
		for (BlockBreakingInfo info : ((WorldRendererAccessor) client.worldRenderer).mapmcbot$getBlockBreakingInfos().values()) {
			final BlockPos pos = info.getPos();

			if (pos.getX() == block[0] && pos.getY() == block[1] && pos.getZ() == block[2]) {
				return info.getStage();
			}
		}

		return -1;
	}

	/**
	 * The blocks as the shape they make together, in the world (depth tested): of each, the faces not
	 * against another of them, just off the block, and their edges but where the face goes on flat
	 * into the next one's.
	 */
	private static void drawCells(List<int[]> blocks, int rgb, double camX, double camY, double camZ) {
		final Set<Long> cells = new HashSet<Long>();

		for (int[] block : blocks) {
			cells.add(cell(block[0], block[1], block[2]));
		}

		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();

		for (int pass = 0; pass < 2; pass++) {
			buffer.begin(pass == 0 ? GL11.GL_QUADS : GL11.GL_LINES, VertexFormats.POSITION_COLOR);

			for (int[] block : blocks) {
				for (int[] face : BotVis.FACES) {
					if (cells.contains(cell(block[0] + face[0], block[1] + face[1], block[2] + face[2]))) {
						continue;
					}

					// The face's axis k, the other two u and v; its plane just off the block.
					final int k = face[0] != 0 ? 0 : face[1] != 0 ? 1 : 2;
					final int u = (k + 1) % 3;
					final int v = (k + 2) % 3;
					final double plane = block[k] + (face[k] > 0 ? 1 : 0) - face[k] * EXPANSION;
					final double[][] corners = new double[4][3];

					for (int c = 0; c < 4; c++) {
						corners[c][k] = plane;
						corners[c][u] = block[u] + (c == 1 || c == 2 ? 1 : 0);
						corners[c][v] = block[v] + (c >= 2 ? 1 : 0);
					}

					if (pass == 0) {
						for (double[] corner : corners) {
							buffer.vertex(corner[0] - camX, corner[1] - camY, corner[2] - camZ).color(r, g, b, FILL_ALPHA).next();
						}

						continue;
					}

					// Edge c from corner c to c + 1, toward the neighbour on it: -v, +u, +v, -u.
					for (int c = 0; c < 4; c++) {
						final int[] step = new int[3];
						step[c == 1 || c == 3 ? u : v] = c == 0 || c == 3 ? -1 : 1;
						final int nx = block[0] + step[0];
						final int ny = block[1] + step[1];
						final int nz = block[2] + step[2];

						if (cells.contains(cell(nx, ny, nz)) && !cells.contains(cell(nx + face[0], ny + face[1], nz + face[2]))) {
							continue;
						}

						final double[] from = corners[c];
						final double[] to = corners[(c + 1) % 4];
						line(buffer, from[0] - camX, from[1] - camY, from[2] - camZ, to[0] - camX, to[1] - camY, to[2] - camZ, r, g, b);
					}
				}
			}

			tessellator.draw();
		}
	}

	/** The block as a key, packed as BlockPos packs it. */
	private static long cell(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
	}

	/** A couple of tones darker: readable on its own colour's box. */
	private static int dark(int rgb) {
		return ((rgb >> 16 & 0xFF) * 9 / 20) << 16 | ((rgb >> 8 & 0xFF) * 9 / 20) << 8 | (rgb & 0xFF) * 9 / 20;
	}

	/** Three quarters of the way to a near black grey: the bot's colour, set well apart from its own. */
	private static int grey(int rgb) {
		return ((rgb >> 16 & 0xFF) + 3 * 0x18) / 4 << 16 | ((rgb >> 8 & 0xFF) + 3 * 0x18) / 4 << 8 | ((rgb & 0xFF) + 3 * 0x18) / 4;
	}

	/** The face {x, y, z} out of the block, just off it, filled. */
	private static void drawFace(int[] block, int[] face, int rgb, double camX, double camY, double camZ) {
		final double[] min = {block[0], block[1], block[2]};
		final double[] max = {block[0] + 1, block[1] + 1, block[2] + 1};

		for (int k = 0; k < 3; k++) {
			if (face[k] != 0) {
				min[k] = max[k] = (face[k] > 0 ? max[k] : min[k]) - face[k] * EXPANSION;
			}
		}

		// A box flat on the face: of its quads the two on it alone have an area.
		drawFill(min[0], min[1], min[2], max[0], max[1], max[2], rgb, FACE_ALPHA / 2, camX, camY, camZ);
	}

	/**
	 * A region {min x, y, z, max x, y, z} flat on a face {x, y, z} as a box, filled and outlined over
	 * everything: as wide as the region along the face, as thick as a point (see drawPoint) across it.
	 */
	private static void drawRegion(double[] region, int[] face, int rgb, double camX, double camY, double camZ) {
		final double[] min = {region[0], region[1], region[2]};
		final double[] max = {region[3], region[4], region[5]};

		for (int k = 0; k < 3; k++) {
			if (face[k] != 0) {
				min[k] -= POINT;
				max[k] += POINT;
			}
		}

		drawFill(min[0], min[1], min[2], max[0], max[1], max[2], rgb, REGION_ALPHA, camX, camY, camZ);
		drawOutline(min[0], min[1], min[2], max[0], max[1], max[2], rgb, camX, camY, camZ);
	}

	/** A point as a small box, over everything. */
	private static void drawPoint(double[] p, int rgb, double camX, double camY, double camZ) {
		drawOutline(p[0] - POINT, p[1] - POINT, p[2] - POINT, p[0] + POINT, p[1] + POINT, p[2] + POINT, rgb, camX, camY, camZ);
	}

	/** From a to b, over everything. */
	private static void drawLine(double[] a, double[] b, int rgb, double camX, double camY, double camZ) {
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);
		line(buffer, a[0] - camX, a[1] - camY, a[2] - camZ, b[0] - camX, b[1] - camY, b[2] - camZ, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	/** The blocks as a line through their centres a bit off their floor, as a path is, over everything. */
	private static void drawBlocks(int[][] blocks, int rgb, double camX, double camY, double camZ) {
		if (blocks.length < 2) {
			return;
		}

		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINE_STRIP, VertexFormats.POSITION_COLOR);

		for (int[] block : blocks) {
			buffer.vertex(block[0] + 0.5 - camX, block[1] + 0.1 - camY, block[2] + 0.5 - camZ).color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, EDGE_ALPHA).next();
		}

		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	/** The nodes of a path {target x, y, z, node x, y, z, ...} as a line through the block centres, over everything. */
	private static void drawPath(int[] path, int rgb, double camX, double camY, double camZ) {
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;

		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINE_STRIP, VertexFormats.POSITION_COLOR);

		for (int i = 3; i < path.length; i += 3) {
			buffer.vertex(path[i] + 0.5 - camX, path[i + 1] + 0.1 - camY, path[i + 2] + 0.5 - camZ).color(r, g, b, EDGE_ALPHA).next();
		}

		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	private static boolean isTooFar(Area area, double camX, double camY, double camZ) {
		final double dx = (area.getMinX() + area.getMaxX() + 1) * 0.5 - camX;
		final double dy = (area.getMinY() + area.getMaxY() + 1) * 0.5 - camY;
		final double dz = (area.getMinZ() + area.getMaxZ() + 1) * 0.5 - camZ;
		return dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ;
	}

	private static void drawBox(double x0, double y0, double z0, double x1, double y1, double z1,
			int rgb, double camX, double camY, double camZ) {
		// The translucent fill respects depth so the box sits in the world...
		drawFill(x0, y0, z0, x1, y1, z1, rgb, FILL_ALPHA, camX, camY, camZ);

		// ...but the outline ignores it, so an area behind a hill can still be found.
		drawOutline(x0, y0, z0, x1, y1, z1, rgb, camX, camY, camZ);
	}

	/** The faces of the box, translucent by alpha, in the world (depth tested). */
	private static void drawFill(double x0, double y0, double z0, double x1, double y1, double z1,
			int rgb, int alpha, double camX, double camY, double camZ) {
		final double ax = x0 - EXPANSION - camX;
		final double ay = y0 - EXPANSION - camY;
		final double az = z0 - EXPANSION - camZ;
		final double bx = x1 + EXPANSION - camX;
		final double by = y1 + EXPANSION - camY;
		final double bz = z1 + EXPANSION - camZ;

		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;

		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();

		buffer.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
		quad(buffer, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz, r, g, b, alpha);
		quad(buffer, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az, r, g, b, alpha);
		quad(buffer, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az, r, g, b, alpha);
		quad(buffer, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz, r, g, b, alpha);
		quad(buffer, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az, r, g, b, alpha);
		quad(buffer, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz, r, g, b, alpha);
		tessellator.draw();
	}

	/** The edges of the box alone, over everything. */
	private static void drawOutline(double x0, double y0, double z0, double x1, double y1, double z1,
			int rgb, double camX, double camY, double camZ) {
		final double ax = x0 - EXPANSION - camX;
		final double ay = y0 - EXPANSION - camY;
		final double az = z0 - EXPANSION - camZ;
		final double bx = x1 + EXPANSION - camX;
		final double by = y1 + EXPANSION - camY;
		final double bz = z1 + EXPANSION - camZ;
		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;
		final Tessellator tessellator = Tessellator.getInstance();
		final BufferBuilder buffer = tessellator.getBuffer();
		GlStateManager.disableDepthTest();
		buffer.begin(GL11.GL_LINES, VertexFormats.POSITION_COLOR);

		line(buffer, ax, ay, az, ax, by, az, r, g, b);
		line(buffer, bx, ay, az, bx, by, az, r, g, b);
		line(buffer, bx, ay, bz, bx, by, bz, r, g, b);
		line(buffer, ax, ay, bz, ax, by, bz, r, g, b);

		for (double y : new double[] {ay, by}) {
			line(buffer, ax, y, az, bx, y, az, r, g, b);
			line(buffer, bx, y, az, bx, y, bz, r, g, b);
			line(buffer, bx, y, bz, ax, y, bz, r, g, b);
			line(buffer, ax, y, bz, ax, y, az, r, g, b);
		}

		tessellator.draw();
		GlStateManager.enableDepthTest();
	}

	private static void quad(BufferBuilder buffer,
			double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4,
			int r, int g, int b, int a) {
		buffer.vertex(x1, y1, z1).color(r, g, b, a).next();
		buffer.vertex(x2, y2, z2).color(r, g, b, a).next();
		buffer.vertex(x3, y3, z3).color(r, g, b, a).next();
		buffer.vertex(x4, y4, z4).color(r, g, b, a).next();
	}

	private static void line(BufferBuilder buffer,
			double x1, double y1, double z1, double x2, double y2, double z2, int r, int g, int b) {
		buffer.vertex(x1, y1, z1).color(r, g, b, EDGE_ALPHA).next();
		buffer.vertex(x2, y2, z2).color(r, g, b, EDGE_ALPHA).next();
	}
}
