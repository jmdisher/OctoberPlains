package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.AspectRegistry;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.utils.Assert;


/**
 * This class contains the logic for allocating, building, and deleting the "layers" of the main scene.  A layer is a
 * single z-level within a cuboid.
 * Note that the actual data buffers describing a layer are constructed in a background thread, only uploaded to the GPU
 * on the main thread.  A fixed number of scratch buffers are used to facilitate this.
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
	/**
	 * The number of scratch buffers we will use for background layer baking.  More will result in fewer skipped frames
	 * of data being copied to the GPU but will result in more memory usage and more wasted CPU time drawing these
	 * potentially useless data elements.
	 */
	public static final int SCRATCH_BUFFER_COUNT = 4;

	private final Environment _environment;
	private final GL20 _gl;
	private final TextureAtlas _textureAtlas;
	private final Map<CuboidAddress, _CuboidMeshes> _layerTextureMeshes;
	private final Queue<ByteBuffer> _scratchGraphicsBuffers;

	// Objects related to the handoff.
	private boolean _keepRunning;
	private final Queue<_RenderRequest> _requests;
	private final Queue<_RenderRequest> _responses;
	private final Thread _background;

	public LayerManager(Environment environment, GL20 gl, TextureAtlas textureAtlas)
	{
		_environment = environment;
		_gl = gl;
		_textureAtlas = textureAtlas;
		_layerTextureMeshes = new HashMap<>();
		_scratchGraphicsBuffers = new LinkedList<>();
		for (int i = 0; i < SCRATCH_BUFFER_COUNT; ++i)
		{
			ByteBuffer buffer = ByteBuffer.allocateDirect(SINGLE_LAYER_TOTAL_BUFFER_BYTES);
			buffer.order(ByteOrder.nativeOrder());
			_scratchGraphicsBuffers.offer(buffer);
		}
		
		// Setup the background processing thread.
		_keepRunning = true;
		_requests = new LinkedList<>();
		_responses = new LinkedList<>();
		_background = new Thread(() -> _backgroundMain()
				, "Layer Baking Thread"
		);
		_background.start();
	}

	public boolean containsCuboid(CuboidAddress address)
	{
		return _layerTextureMeshes.containsKey(address);
	}

	public void storeCuboid(IReadOnlyCuboidData cuboid)
	{
		// This could be new or a replacement so see if we need to clean anything up.
		CuboidAddress address = cuboid.getCuboidAddress();
		// Note that we don't actually delete any of the old buffers - just reset their generation so they will be regenerated in the background.
		_CuboidMeshes cuboidTextures = _layerTextureMeshes.get(address);
		if (null != cuboidTextures)
		{
			// This already exists so just update the data and increment the generation number so it is re-baked in the background.
			cuboidTextures.data = cuboid;
			cuboidTextures.dataGeneration += 1;
		}
		else
		{
			// This is new so just add it.
			_layerTextureMeshes.put(address, new _CuboidMeshes(cuboid));
		}
		
		// We also need to the z-31 layer of the cuboid below this for rebake, since the lighting may have changed.
		_CuboidMeshes below = _layerTextureMeshes.get(address.getRelative(0, 0, -1));
		if (null != below)
		{
			int existing = below.buffersByZ[31];
			if (0 != existing)
			{
				// 0 is not a valid generation number so this will cause a background re-bake.
				below.bufferGeneration[31] = 0;
			}
		}
	}

	public int getBakedLayer(CuboidAddress address, byte zLayer)
	{
		int buffer = 0;
		
		// First, see if this is even a loaded cuboid.
		_CuboidMeshes cuboidTextures = _layerTextureMeshes.get(address);
		if (null != cuboidTextures)
		{
			// Now, get the layer buffer object.
			buffer = cuboidTextures.buffersByZ[zLayer];
			
			// If this is missing or stale, we want to issue a background request.
			if ((0 == buffer) || (cuboidTextures.dataGeneration != cuboidTextures.bufferGeneration[zLayer]))
			{
				// We want to issue the background baking request so see if we have a scratch buffer.
				ByteBuffer scratch = _scratchGraphicsBuffers.poll();
				if (null != scratch)
				{
					// We can issue the request so update the generation numbers to mark that it is happening and enqueue this.
					cuboidTextures.bufferGeneration[zLayer] = cuboidTextures.dataGeneration;
					IReadOnlyCuboidData aboveCuboid = null;
					if (31 == zLayer)
					{
						_CuboidMeshes aboveTextures = _layerTextureMeshes.get(address.getRelative(0, 0, 1));
						aboveCuboid = (null != aboveTextures) ? aboveTextures.data : null;
					}
					_RenderRequest request = new _RenderRequest(cuboidTextures.data
							, aboveCuboid
							, zLayer
							, scratch
					);
					_enqueueRequest(request);
				}
			}
		}
		return buffer;
	}

	public void removeCuboid(CuboidAddress address)
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

	/**
	 * Will check to see if any background layer bake requests have been completed and will upload the first one to the
	 * GPU if any are found.
	 */
	public void completeBackgroundBakeRequest()
	{
		_RenderRequest response = _dequeueResponse();
		if (null != response)
		{
			// Make sure that this is still here.
			_CuboidMeshes cuboidTextures = _layerTextureMeshes.get(response.data.getCuboidAddress());
			if (null != cuboidTextures)
			{
				int oldBuffer = cuboidTextures.buffersByZ[response.zLayer];
				if (oldBuffer > 0)
				{
					_gl.glDeleteBuffer(oldBuffer);
				}
				int buffer = _uploadBufferData(response.scratchBuffer);
				Assert.assertTrue(buffer > 0);
				cuboidTextures.buffersByZ[response.zLayer] = buffer;
			}
			
			// Salvage the scratch buffer.
			_scratchGraphicsBuffers.add(response.scratchBuffer);
		}
	}

	/**
	 * Shuts down the background baking thread.
	 */
	public void shutdown()
	{
		synchronized(this)
		{
			_keepRunning = false;
			this.notifyAll();
		}
		try
		{
			_background.join();
		}
		catch (InterruptedException e)
		{
			throw Assert.unexpected(e);
		}
	}


	private void _backgroundMain()
	{
		_RenderRequest request = _backgroundGetRequest(null);
		while (null != request)
		{
			// Populate the buffer.
			_backgroundDefineLayerTextureBuffer(request.data
					, request.aboveCuboid
					, request.zLayer
					, request.scratchBuffer
			);
			
			// Pass this back since the buffer is now full.
			request = _backgroundGetRequest(request);
		}
	}

	private synchronized _RenderRequest _backgroundGetRequest(_RenderRequest response)
	{
		if (null != response)
		{
			_responses.add(response);
			// (We don't notify here since the foreground thread never waits on this response - just picks it up later)
		}
		while (_keepRunning && _requests.isEmpty())
		{
			try
			{
				this.wait();
			}
			catch (InterruptedException e)
			{
				// Interruption not used.
				throw Assert.unexpected(e);
			}
		}
		return _keepRunning
				? _requests.poll()
				: null
		;
	}

	private void _backgroundDefineLayerTextureBuffer(IReadOnlyCuboidData cuboid
			, IReadOnlyCuboidData aboveCuboid
			, byte zLayer
			, ByteBuffer bufferToFill
	)
	{
		// Populate the common mesh.
		((java.nio.Buffer) bufferToFill).position(0);
		FloatBuffer textureBuffer = bufferToFill.asFloatBuffer();
		float textureSize0 = _textureAtlas.tileCoordinateSize;
		float textureSize1 = _textureAtlas.auxCoordinateSize;
		for (int y = 0; y < CUBOID_EDGE_TILE_COUNT; ++y)
		{
			for (int x = 0; x < CUBOID_EDGE_TILE_COUNT; ++x)
			{
				BlockAddress blockAddress = new BlockAddress((byte)x, (byte)y, zLayer);
				BlockProxy proxy = new BlockProxy(blockAddress, cuboid);
				Block block = proxy.getBlock();
				
				// The primary texture is just based on whatever the item type is.
				float[] uv0 = _textureAtlas.baseOfTileTexture(block.item());
				float textureBase0U = uv0[0];
				float textureBase0V = uv0[1];
				
				// NOTE:  We invert the textures here (probably not ideal).
				float[] tl0 = new float[]{textureBase0U, textureBase0V};
				float[] tr0 = new float[]{textureBase0U + textureSize0, textureBase0V};
				float[] br0 = new float[]{textureBase0U + textureSize0, textureBase0V + textureSize0};
				float[] bl0 = new float[]{textureBase0U, textureBase0V + textureSize0};
				
				// Handle the secondary texture to blend in.
				boolean isCrafting = (null != proxy.getCrafting());
				short damage = proxy.getDamage();
				// Note that we will get an empty inventory if an inventory is supported.
				Inventory blockInventory = proxy.getInventory();
				boolean hasDebrisInventory = _environment.blocks.permitsEntityMovement(block) && (null != blockInventory) && !blockInventory.sortedKeys().isEmpty();
				
				// Apply the rules for how we prioritize secondary textures.
				TextureAtlas.Auxiliary aux;
				if (isCrafting)
				{
					aux = TextureAtlas.Auxiliary.ACTIVE_STATION;
				}
				else if (hasDebrisInventory)
				{
					aux = TextureAtlas.Auxiliary.DEBRIS;
				}
				else if (damage > 0)
				{
					// We will favour showing cracks at a low damage, so the feedback is obvious
					float damaged = (float) damage / (float)_environment.damage.getToughness(block);
					if (damaged > 0.6f)
					{
						aux = TextureAtlas.Auxiliary.BREAK_HEAVY;
					}
					else if (damaged > 0.3f)
					{
						aux = TextureAtlas.Auxiliary.BREAK_MEDIUM;
					}
					else
					{
						aux = TextureAtlas.Auxiliary.BREAK_LIGHT;
					}
				}
				else
				{
					aux = TextureAtlas.Auxiliary.NONE;
				}
				float[] uv1 = _textureAtlas.baseOfAuxTexture(aux);
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
	}

	private int _uploadBufferData(ByteBuffer buffer)
	{
		((java.nio.Buffer) buffer).position(0);
		
		int commonTextures = _gl.glGenBuffer();
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonTextures);
		_gl.glBufferData(GL20.GL_ARRAY_BUFFER, SINGLE_LAYER_TOTAL_BUFFER_BYTES, buffer.asFloatBuffer(), GL20.GL_DYNAMIC_DRAW);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(2);
		_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 2 * Float.BYTES);
		_gl.glEnableVertexAttribArray(3);
		_gl.glVertexAttribPointer(3, 1, GL20.GL_FLOAT, false, 5 * Float.BYTES, 4 * Float.BYTES);
		return commonTextures;
	}

	private synchronized void _enqueueRequest(_RenderRequest request)
	{
		_requests.add(request);
		this.notifyAll();
	}

	private synchronized _RenderRequest _dequeueResponse()
	{
		return _responses.poll();
	}


	private static class _CuboidMeshes
	{
		public IReadOnlyCuboidData data;
		public int dataGeneration;
		public final int[] buffersByZ;
		public final int[] bufferGeneration;
		
		public _CuboidMeshes(IReadOnlyCuboidData data)
		{
			this.data = data;
			this.dataGeneration = 1;
			this.buffersByZ = new int[32];
			this.bufferGeneration = new int[32];
		}
	}

	private static record _RenderRequest(IReadOnlyCuboidData data
			, IReadOnlyCuboidData aboveCuboid
			, byte zLayer
			, ByteBuffer scratchBuffer
	)
	{}
}
