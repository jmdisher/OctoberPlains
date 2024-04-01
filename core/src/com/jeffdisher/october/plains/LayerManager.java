package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.AspectRegistry;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.utils.Assert;


/**
 * This class contains the logic for allocating, building, and deleting the "layers" of the main scene.  A layer is a
 * single z-level within a cuboid.
 */
public class LayerManager
{
	public static final int CUBOID_EDGE_TILE_COUNT = 32;
	// The texture buffer just has the 2 sets of textures:  the main atlas and the secondary atlas.
	public static final int SINGLE_VERTEX_BUFFER_BYTES = 0
			// UV texture coordinates for main texture atlas.
			+ (2 * Float.BYTES)
			// UV texture coordinates for secondary texture atlas.
			+ (2 * Float.BYTES)
			// A float for the light multiplier.
			+ Float.BYTES
	;
	public static final int SINGLE_LAYER_TOTAL_BUFFER_BYTES = 1
			// tiles per layer
			* (CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT)
			// triangles per tile
			* 2
			// vertices per triangle
			* 3
			// Bytes per vertex.
			* SINGLE_VERTEX_BUFFER_BYTES
	;

	private final Environment _environment;
	private final GL20 _gl;
	private final TextureAtlas _textureAtlas;
	private final Map<CuboidAddress, _CuboidMeshes> _layerTextureMeshes;
	private final ByteBuffer _scratchGraphicsBuffer;

	public LayerManager(Environment environment, GL20 gl, TextureAtlas textureAtlas)
	{
		_environment = environment;
		_gl = gl;
		_textureAtlas = textureAtlas;
		_layerTextureMeshes = new HashMap<>();
		_scratchGraphicsBuffer = ByteBuffer.allocateDirect(SINGLE_LAYER_TOTAL_BUFFER_BYTES);
		_scratchGraphicsBuffer.order(ByteOrder.nativeOrder());
	}

	public boolean containsCuboid(CuboidAddress address)
	{
		return _layerTextureMeshes.containsKey(address);
	}

	public void storeCuboid(IReadOnlyCuboidData cuboid)
	{
		// This could be new or a replacement so see if we need to clean anything up.
		CuboidAddress address = cuboid.getCuboidAddress();
		_cleanUpCuboid(address);
		// We also need to clear out the z-31 layer of the cuboid below this, since the lighting may have changed.
		_CuboidMeshes below = _layerTextureMeshes.get(address.getRelative(0, 0, -1));
		if (null != below)
		{
			int existing = below.buffersByZ[31];
			if (0 != existing)
			{
				_gl.glDeleteBuffer(existing);
				below.buffersByZ[31] = 0;
			}
		}
		_layerTextureMeshes.put(address, new _CuboidMeshes(cuboid));
	}

	public int getOrBakeLayer(CuboidAddress address, byte zLayer)
	{
		int buffer = 0;
		_CuboidMeshes cuboidTextures = _layerTextureMeshes.get(address);
		if (null != cuboidTextures)
		{
			// We either already have this or we can come up with a baked version of it.
			buffer = cuboidTextures.buffersByZ[zLayer];
			if (0 == buffer)
			{
				// This is missing so bake it now.
				IReadOnlyCuboidData aboveCuboid = null;
				if (31 == zLayer)
				{
					_CuboidMeshes aboveTextures = _layerTextureMeshes.get(address.getRelative(0, 0, 1));
					aboveCuboid = (null != aboveTextures) ? aboveTextures.data : null;
				}
				buffer = _defineLayerTextureBuffer(cuboidTextures.data, aboveCuboid, zLayer);
				Assert.assertTrue(buffer > 0);
				cuboidTextures.buffersByZ[zLayer] = buffer;
			}
		}
		return buffer;
	}

	public void removeCuboid(CuboidAddress address)
	{
		_cleanUpCuboid(address);
	}


	private int _defineLayerTextureBuffer(IReadOnlyCuboidData cuboid
			, IReadOnlyCuboidData aboveCuboid
			, byte zLayer
	)
	{
		// Populate the common mesh.
		((java.nio.Buffer) _scratchGraphicsBuffer).position(0);
		FloatBuffer textureBuffer = _scratchGraphicsBuffer.asFloatBuffer();
		float textureSize0 = _textureAtlas.coordinateSize;
		float textureSize1 = _textureAtlas.secondaryCoordinateSize;
		for (int y = 0; y < CUBOID_EDGE_TILE_COUNT; ++y)
		{
			for (int x = 0; x < CUBOID_EDGE_TILE_COUNT; ++x)
			{
				BlockAddress blockAddress = new BlockAddress((byte)x, (byte)y, zLayer);
				short blockValue = cuboid.getData15(AspectRegistry.BLOCK, blockAddress);
				
				// Note that we generally just map the block values directly but there is a special case of an air block with an inventory (debris).
				Inventory inventory = (0 == blockValue)
						? cuboid.getDataSpecial(AspectRegistry.INVENTORY, blockAddress)
						: null
				;
				float[] uv0 = (null == inventory)
						? _textureAtlas.baseOfTexture(blockValue)
						: _textureAtlas.baseOfDebrisTexture()
				;
				float textureBase0U = uv0[0];
				float textureBase0V = uv0[1];
				
				// NOTE:  We invert the textures here (probably not ideal).
				float[] tl0 = new float[]{textureBase0U, textureBase0V};
				float[] tr0 = new float[]{textureBase0U + textureSize0, textureBase0V};
				float[] br0 = new float[]{textureBase0U + textureSize0, textureBase0V + textureSize0};
				float[] bl0 = new float[]{textureBase0U, textureBase0V + textureSize0};
				
				// Handle the secondary texture to blend in.
				boolean isCrafting = (null != cuboid.getDataSpecial(AspectRegistry.CRAFTING, blockAddress));
				short damage = cuboid.getData15(AspectRegistry.DAMAGE, blockAddress);
				// TODO:  Fix how we organize this since we are just hard-coding indices.
				int secondaryIndex = 0;
				if (isCrafting)
				{
					secondaryIndex = 4;
				}
				else if (damage > 0)
				{
					// We will favour showing cracks at a low damage, so the feedback is obvious
					float damaged = (float) damage / (float)_environment.damage.getToughness(_environment.blocks.BLOCKS_BY_TYPE[blockValue]);
					if (damaged > 0.6f)
					{
						secondaryIndex = 3;
					}
					else if (damaged > 0.3f)
					{
						secondaryIndex = 2;
					}
					else
					{
						secondaryIndex = 1;
					}
				}
				float[] uv1 = _textureAtlas.baseOfSecondaryTexture(secondaryIndex);
				float textureBase1U = uv1[0];
				float textureBase1V = uv1[1];
				
				// NOTE:  We invert the textures here (probably not ideal).
				float[] tl1 = new float[]{textureBase1U, textureBase1V};
				float[] tr1 = new float[]{textureBase1U + textureSize1, textureBase1V};
				float[] br1 = new float[]{textureBase1U + textureSize1, textureBase1V + textureSize1};
				float[] bl1 = new float[]{textureBase1U, textureBase1V + textureSize1};
				
				// We also want the light level of the block above this (since we are looking down at the layer).
				byte light = (31 != zLayer)
						? cuboid.getData7(AspectRegistry.LIGHT, new BlockAddress(blockAddress.x(), blockAddress.y(), (byte)(blockAddress.z() + 1)))
						: (null != aboveCuboid) ? aboveCuboid.getData7(AspectRegistry.LIGHT, new BlockAddress(blockAddress.x(), blockAddress.y(), (byte)0)) : 0
				;
				float lightMultiplier = 0.5f + (((float)light) / 15.0f);
				
				textureBuffer.put(bl0);
				textureBuffer.put(bl1);
				textureBuffer.put(lightMultiplier);
				textureBuffer.put(br0);
				textureBuffer.put(br1);
				textureBuffer.put(lightMultiplier);
				textureBuffer.put(tr0);
				textureBuffer.put(tr1);
				textureBuffer.put(lightMultiplier);
				
				textureBuffer.put(bl0);
				textureBuffer.put(bl1);
				textureBuffer.put(lightMultiplier);
				textureBuffer.put(tr0);
				textureBuffer.put(tr1);
				textureBuffer.put(lightMultiplier);
				textureBuffer.put(tl0);
				textureBuffer.put(tl1);
				textureBuffer.put(lightMultiplier);
			}
		}
		((java.nio.Buffer) _scratchGraphicsBuffer).position(0);
		
		int commonTextures = _gl.glGenBuffer();
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonTextures);
		_gl.glBufferData(GL20.GL_ARRAY_BUFFER, SINGLE_LAYER_TOTAL_BUFFER_BYTES, _scratchGraphicsBuffer.asFloatBuffer(), GL20.GL_DYNAMIC_DRAW);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(2);
		_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 2 * Float.BYTES);
		_gl.glEnableVertexAttribArray(3);
		_gl.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 5 * Float.BYTES, 4 * Float.BYTES);
		return commonTextures;
	}

	private void _cleanUpCuboid(CuboidAddress address)
	{
		_CuboidMeshes cuboidTextures = _layerTextureMeshes.remove(address);
		if (null != cuboidTextures)
		{
			// Clear any existing buffers.
			int[] layers = cuboidTextures.buffersByZ;
			for (int layer : layers)
			{
				if (0 != layer)
				{
					_gl.glDeleteBuffer(layer);
				}
			}
		}
	}


	private static class _CuboidMeshes
	{
		private final IReadOnlyCuboidData data;
		private final int[] buffersByZ;
		
		public _CuboidMeshes(IReadOnlyCuboidData data)
		{
			this.data = data;
			this.buffersByZ = new int[32];
		}
	}
}
