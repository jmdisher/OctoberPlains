package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.DamageAspect;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.registries.AspectRegistry;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.utils.Assert;


public class RenderSupport
{
	// The screen is 2.0/2.0 (-1.0 - 1.0) and we want roughly 40x40 tiles on screen, so use this tile edge size.
	public static final float TILE_EDGE_SIZE = 0.05f;
	public static final int CUBOID_EDGE_TILE_COUNT = 32;
	public static final int VERTICES_PER_SQUARE = 6;

	public static int fullyLinkedProgram(GL20 gl, String vertexSource, String fragmentSource, String[] attributesInOrder)
	{
		return _fullyLinkedProgram(gl, vertexSource, fragmentSource, attributesInOrder);
	}


	// We need to render 2 kinds of things:  (1) Cuboid layers, (2) entities.
	// We will just use a single pair of shaders, at least for now, for both of these cases:
	// -vertex shader will move the rendering location by x/y uniform and pass through the texture u/v coordinates attribute
	// -fragment shader will sample the referenced texture coordinates and apply an alpha value
	// These shaders will be used for both rendering the layers and also the entities, just using different uniforms.
	private final GL20 _gl;
	private final TextureAtlas _textureAtlas;
	
	private int _program;
	private int _uOffset;
	private int _uScale;
	private int _uSceneScale;
	private int _uTexture0;
	private int _uTexture1;
	private int _uLayerBrightness;
	private int _uLayerAlpha;
	private int _uColourBias;
	private int _entityBuffer;
	private int _layerMeshBuffer;

	private Entity _thisEntity;
	private final Map<Integer, Entity> _otherEntitiesById;
	private final Map<CuboidAddress, _CuboidMeshes> _layerTextureMeshes;
	private float _currentSceneScale;

	public RenderSupport(GL20 gl, TextureAtlas textureAtlas)
	{
		_gl = gl;
		_textureAtlas = textureAtlas;
		
		// We want to honour alpha channels.
		_gl.glEnable(GL20.GL_BLEND);
		_gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
		
		// Create the shader program.
		_program = _fullyLinkedProgram(_gl
				, "#version 100\n"
						+ "attribute vec2 aPosition;\n"
						+ "attribute vec2 aTexture0;\n"
						+ "attribute vec2 aTexture1;\n"
						+ "uniform vec2 uOffset;\n"
						+ "uniform float uScale;\n"
						+ "uniform float uSceneScale;\n"
						+ "varying vec2 vTexture0;\n"
						+ "varying vec2 vTexture1;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vTexture0 = aTexture0;\n"
						+ "	vTexture1 = aTexture1;\n"
						+ "	gl_Position = vec4(uSceneScale * ((uScale * aPosition.x) + uOffset.x), uSceneScale * ((uScale * aPosition.y) + uOffset.y), 0.0, 1.0);\n"
						+ "}\n"
				, "#version 100\n"
						+ "precision mediump float;\n"
						+ "uniform sampler2D uTexture0;\n"
						+ "uniform sampler2D uTexture1;\n"
						+ "uniform float uLayerBrightness;\n"
						+ "uniform float uLayerAlpha;\n"
						+ "uniform vec4 uColourBias;\n"
						+ "varying vec2 vTexture0;\n"
						+ "varying vec2 vTexture1;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec4 tex0 = texture2D(uTexture0, vTexture0);\n"
						+ "	vec4 tex1 = texture2D(uTexture1, vTexture1);\n"
						+ "	vec4 tex = mix(tex0, tex1, tex1.a);\n"
						+ "	vec4 biased = vec4(clamp(uColourBias.r + tex.r, 0.0, 1.0), clamp(uColourBias.g + tex.g, 0.0, 1.0), clamp(uColourBias.b + tex.b, 0.0, 1.0), clamp(uColourBias.a + tex.a, 0.0, 1.0));\n"
						+ "	gl_FragColor = vec4(uLayerBrightness * biased.r, uLayerBrightness * biased.g, uLayerBrightness * biased.b, uLayerAlpha * biased.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture0",
						"aTexture1",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uScale = _gl.glGetUniformLocation(_program, "uScale");
		_uSceneScale = _gl.glGetUniformLocation(_program, "uSceneScale");
		_uTexture0 = _gl.glGetUniformLocation(_program, "uTexture0");
		_uTexture1 = _gl.glGetUniformLocation(_program, "uTexture1");
		_uLayerBrightness = _gl.glGetUniformLocation(_program, "uLayerBrightness");
		_uLayerAlpha = _gl.glGetUniformLocation(_program, "uLayerAlpha");
		_uColourBias = _gl.glGetUniformLocation(_program, "uColourBias");
		
		// Define the entity mesh and texture.
		_entityBuffer = _defineEntityBuffer(_gl, _textureAtlas);
		
		// Define the layer mesh.
		_layerMeshBuffer = _defineLayerMeshBuffer(_gl);
		
		_otherEntitiesById = new HashMap<>();
		_layerTextureMeshes = new HashMap<>();
		_currentSceneScale = 1.0f;
	}

	/**
	 * Renders a single frame of the scene.
	 * 
	 * @param selectedLocation The block currently selected in the UI.
	 */
	public void renderScene(AbsoluteLocation selectedLocation)
	{
		// We render this relative to the entity, so figure out where it is.
		EntityLocation entityLocation = _thisEntity.location();
		AbsoluteLocation entityBlockLocation = entityLocation.getBlockLocation();
		float x = entityLocation.x();
		float y = entityLocation.y();
		
		// Determine which tile is selected under the mouse.
		CuboidAddress selectedCuboid = null;
		BlockAddress selectedBlock = null;
		if (null != selectedLocation)
		{
			selectedCuboid = selectedLocation.getCuboidAddress();
			
			// This may not be here if the server hasn't sent it yet.
			selectedBlock = _layerTextureMeshes.containsKey(selectedCuboid)
					? selectedLocation.getBlockAddress()
					: null
			;
		}
		
		// Reset screen.
		_gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		_gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		_gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		_gl.glUseProgram(_program);
		
		// Make sure that the texture atlas is active.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glActiveTexture(GL20.GL_TEXTURE1);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.secondaryTexture);
		_gl.glUniform1i(_uTexture1, 1);
		
		// Set any starting uniform values.
		_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
		_gl.glUniform1f(_uScale, 1.0f);
		_gl.glUniform1f(_uSceneScale, _currentSceneScale);
		
		// We want to render 9 tiles with 3 layers:  3x3x3, centred around the entity location.
		// (technically 4 tiles with 3 layers would be enough but that would require some extra logic)
		float layerBrightness = 0.50f;
		for (int zOffset = -1; zOffset <= 1; ++zOffset)
		{
			_gl.glUniform1f(_uLayerBrightness, layerBrightness);
			_gl.glUniform1f(_uLayerAlpha, (1 == zOffset) ? 0.5f : 1.0f);
			layerBrightness += 0.25f;
			for (int xOffset = -CUBOID_EDGE_TILE_COUNT; xOffset <= CUBOID_EDGE_TILE_COUNT; xOffset += CUBOID_EDGE_TILE_COUNT)
			{
				for (int yOffset = -CUBOID_EDGE_TILE_COUNT; yOffset <= CUBOID_EDGE_TILE_COUNT; yOffset += CUBOID_EDGE_TILE_COUNT)
				{
					AbsoluteLocation offsetLocation = entityBlockLocation.getRelative(xOffset, yOffset, zOffset);
					CuboidAddress address = offsetLocation.getCuboidAddress();
					
					_CuboidMeshes cuboidTextures = _layerTextureMeshes.get(address);
					// This may not be here if the server hasn't sent it yet.
					if (null != cuboidTextures)
					{
						byte zLayer = offsetLocation.getBlockAddress().z();
						if (0 == cuboidTextures.buffersByZ[zLayer])
						{
							// We need to populate this layer.
							cuboidTextures.buffersByZ[zLayer] = _defineLayerTextureBuffer(_gl, _textureAtlas, cuboidTextures.data, zLayer);
						}
						int buffer = cuboidTextures.buffersByZ[zLayer];
						
						// Be sure to position the camera above the entity, so calculate the offset where we will draw this layer.
						float xCamera = TILE_EDGE_SIZE * ((float)(address.x() * CUBOID_EDGE_TILE_COUNT) - x);
						float yCamera = TILE_EDGE_SIZE * ((float)(address.y() * CUBOID_EDGE_TILE_COUNT) - y);
						_gl.glUniform2f(_uOffset, xCamera, yCamera);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerMeshBuffer);
						_gl.glEnableVertexAttribArray(0);
						_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, buffer);
						_gl.glEnableVertexAttribArray(1);
						_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
						_gl.glEnableVertexAttribArray(2);
						_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
						
						_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT * VERTICES_PER_SQUARE);
						
						// Check if this is where the selected tile is and then re-draw it to highlight it.
						if ((null != selectedBlock) && (zLayer == selectedBlock.z()) && selectedCuboid.equals(address))
						{
							// Give it a pinkish hue (with added alpha so we can select air blocks if placing a block).
							_gl.glUniform4f(_uColourBias, 0.5f, 0.0f, 0.5f, 0.5f);
							
							// Redraw the single tile.
							_gl.glDrawArrays(GL20.GL_TRIANGLES, VERTICES_PER_SQUARE * ((CUBOID_EDGE_TILE_COUNT * selectedBlock.y()) + selectedBlock.x()), VERTICES_PER_SQUARE);
							
							_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
						}
					}
				}
			}
			
			if (0 == zOffset)
			{
				// Draw the entity.
				_drawEntity(0.0f, 0.0f, _thisEntity.volume().width());
			}
			
			// See if there are any other entities at this z-level (we should organize this differently, or pre-sort it, in the future).
			int thisZ = Math.round(_thisEntity.location().z()) + zOffset;
			for (Entity otherEntity : _otherEntitiesById.values())
			{
				EntityLocation location = otherEntity.location();
				int otherZ = Math.round(location.z());
				if (thisZ == otherZ)
				{
					// Figure out the offset.
					float xOffset = TILE_EDGE_SIZE * (location.x() - entityLocation.x());
					float yOffset = TILE_EDGE_SIZE * (location.y() - entityLocation.y());
					float scale = otherEntity.volume().width();
					_drawEntity(xOffset, yOffset, scale);
				}
			}
		}
	}

	public void setThisEntity(Entity thisEntity)
	{
		_thisEntity = thisEntity;
	}

	public void setOneCuboid(IReadOnlyCuboidData cuboid)
	{
		// TODO:  This needs to be more precise - don't regenerate the whole cuboid and all aspects.
		CuboidAddress address = cuboid.getCuboidAddress();
		_cleanUpCuboid(address);
		// Add the empty mesh container (these will be lazily populated).
		_layerTextureMeshes.put(address, new _CuboidMeshes(cuboid));
	}

	public void removeCuboid(CuboidAddress address)
	{
		_cleanUpCuboid(address);
	}

	public void setOtherEntity(Entity entity)
	{
		_otherEntitiesById.put(entity.id(), entity);
	}

	public void removeEntity(int entityId)
	{
		Entity old = _otherEntitiesById.remove(entityId);
		// This must have already been here.
		Assert.assertTrue(null != old);
	}

	public void changeZoom(float degree)
	{
		_currentSceneScale += degree;
		if (_currentSceneScale < 1.0f)
		{
			_currentSceneScale = 1.0f;
		}
		else if (_currentSceneScale > 4.0f)
		{
			_currentSceneScale = 4.0f;
		}
	}

	public float getZoom()
	{
		return _currentSceneScale;
	}


	private static int _fullyLinkedProgram(GL20 gl, String vertexSource, String fragmentSource, String[] attributesInOrder)
	{
		int program = gl.glCreateProgram();
		_compileAndAttachShader(gl, program, GL20.GL_VERTEX_SHADER, vertexSource);
		_compileAndAttachShader(gl, program, GL20.GL_FRAGMENT_SHADER, fragmentSource);
		for (int index = 0; index < attributesInOrder.length; ++index)
		{
			gl.glBindAttribLocation(program, index, attributesInOrder[index]);
		}
		gl.glLinkProgram(program);
		return program;
	}

	private static int _compileAndAttachShader(GL20 gl, int program, int shaderType, String source)
	{
		int shader = gl.glCreateShader(shaderType);
		gl.glShaderSource(shader, source);
		gl.glCompileShader(shader);
		ByteBuffer direct = ByteBuffer.allocateDirect(Integer.BYTES);
		direct.order(ByteOrder.nativeOrder());
		IntBuffer buffer = direct.asIntBuffer();
		buffer.put(-1);
		((java.nio.Buffer) buffer).flip();
		gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, buffer);
		if (1 != buffer.get())
		{
			throw new AssertionError("Failed to compile");
		}
		gl.glAttachShader(program, shader);
		return shader;
	}

	private static int _defineEntityBuffer(GL20 gl, TextureAtlas atlas)
	{
		float textureSize0 = atlas.coordinateSize;
		float textureSize1 = atlas.secondaryCoordinateSize;
		float[] uv0 = atlas.baseOfPlayerTexture();
		float textureBase0U = uv0[0];
		float textureBase0V = uv0[1];
		float[] uv1 = atlas.baseOfSecondaryTexture(0);
		float textureBase1U = uv1[0];
		float textureBase1V = uv1[1];
		float[] vertices = new float[] {
				0.0f, TILE_EDGE_SIZE,
					textureBase0U, textureBase0V + textureSize0,
					textureBase1U, textureBase1V + textureSize1,
				0.0f, 0.0f,
					textureBase0U, textureBase0V,
					textureBase1U, textureBase1V,
				TILE_EDGE_SIZE, 0.0f,
					textureBase0U + textureSize0, textureBase0V,
					textureBase1U + textureSize1, textureBase1V,
				
				TILE_EDGE_SIZE, 0.0f,
					textureBase0U + textureSize0, textureBase0V,
					textureBase1U + textureSize1, textureBase1V,
				TILE_EDGE_SIZE, TILE_EDGE_SIZE,
					textureBase0U + textureSize0, textureBase0V + textureSize0,
					textureBase1U + textureSize1, textureBase1V + textureSize1,
				 0.0f, TILE_EDGE_SIZE,
					textureBase0U, textureBase0V + textureSize0,
					textureBase1U, textureBase1V + textureSize1,
		};
		ByteBuffer direct = ByteBuffer.allocateDirect(vertices.length * Float.BYTES);
		direct.order(ByteOrder.nativeOrder());
		for (float f : vertices)
		{
			direct.putFloat(f);
		}
		((java.nio.Buffer) direct).flip();
		
		int entityBuffer = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, entityBuffer);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, direct.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 2 * Float.BYTES);
		gl.glEnableVertexAttribArray(2);
		gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 4 * Float.BYTES);
		return entityBuffer;
	}

	private static int _defineLayerMeshBuffer(GL20 gl)
	{
		// We render each layer as an abstract 32x32 tile mesh.
		// The common mesh has 2 floats per vertex:  x and y (z is constant 0.0 for everything).
		int commonLayerSizeBytes = 1
				// tiles per layer
				* (CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT)
				// triangles per tile
				* 2
				// vertices per triangle
				* 3
				// xy per vertex
				* (Float.BYTES * 2)
		;
		ByteBuffer commonData = ByteBuffer.allocateDirect(commonLayerSizeBytes);
		commonData.order(ByteOrder.nativeOrder());
		// Populate the common mesh.
		FloatBuffer meshBuffer = commonData.asFloatBuffer();
		for (int y = 0; y < CUBOID_EDGE_TILE_COUNT; ++y)
		{
			for (int x = 0; x < CUBOID_EDGE_TILE_COUNT; ++x)
			{
				float xCoord = (TILE_EDGE_SIZE * x);
				float yCoord = (TILE_EDGE_SIZE * y);
				
				float[] bl = new float[]{xCoord, yCoord};
				float[] br = new float[]{xCoord + TILE_EDGE_SIZE, yCoord};
				float[] tr = new float[]{xCoord + TILE_EDGE_SIZE, yCoord + TILE_EDGE_SIZE};
				float[] tl = new float[]{xCoord, yCoord + TILE_EDGE_SIZE};
				
				meshBuffer.put(bl);
				meshBuffer.put(br);
				meshBuffer.put(tr);
				
				meshBuffer.put(bl);
				meshBuffer.put(tr);
				meshBuffer.put(tl);
			}
		}
		((java.nio.Buffer) commonData).position(0);
		
		int commonMesh = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonMesh);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, commonLayerSizeBytes, commonData.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
		return commonMesh;
	}

	private static int _defineLayerTextureBuffer(GL20 gl, TextureAtlas atlas, IReadOnlyCuboidData cuboid, byte zLayer)
	{
		// The texture buffer just has the 2 sets of textures:  the main atlas and the secondary atlas.
		int singleLayerSizeBytes = 1
				// tiles per layer
				* (CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT)
				// triangles per tile
				* 2
				// vertices per triangle
				* 3
				// uv per vertex (both sets)
				* (Float.BYTES * (2 * 2))
		;
		ByteBuffer singleLayerData = ByteBuffer.allocateDirect(singleLayerSizeBytes);
		singleLayerData.order(ByteOrder.nativeOrder());
		// Populate the common mesh.
		FloatBuffer textureBuffer = singleLayerData.asFloatBuffer();
		float textureSize0 = atlas.coordinateSize;
		float textureSize1 = atlas.secondaryCoordinateSize;
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
						? atlas.baseOfTexture(blockValue)
						: atlas.baseOfDebrisTexture()
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
					float damaged = (float) damage / (float)DamageAspect.getToughness(ItemRegistry.ITEMS_BY_TYPE[blockValue]);
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
				float[] uv1 = atlas.baseOfSecondaryTexture(secondaryIndex);
				float textureBase1U = uv1[0];
				float textureBase1V = uv1[1];
				
				// NOTE:  We invert the textures here (probably not ideal).
				float[] tl1 = new float[]{textureBase1U, textureBase1V};
				float[] tr1 = new float[]{textureBase1U + textureSize1, textureBase1V};
				float[] br1 = new float[]{textureBase1U + textureSize1, textureBase1V + textureSize1};
				float[] bl1 = new float[]{textureBase1U, textureBase1V + textureSize1};
				
				textureBuffer.put(bl0);
				textureBuffer.put(bl1);
				textureBuffer.put(br0);
				textureBuffer.put(br1);
				textureBuffer.put(tr0);
				textureBuffer.put(tr1);
				
				textureBuffer.put(bl0);
				textureBuffer.put(bl1);
				textureBuffer.put(tr0);
				textureBuffer.put(tr1);
				textureBuffer.put(tl0);
				textureBuffer.put(tl1);
			}
		}
		((java.nio.Buffer) singleLayerData).position(0);
		
		int commonTextures = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonTextures);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, singleLayerSizeBytes, singleLayerData.asFloatBuffer(), GL20.GL_DYNAMIC_DRAW);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(2);
		gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		return commonTextures;
	}

	private void _drawEntity(float xOffset, float yOffset, float scale)
	{
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glActiveTexture(GL20.GL_TEXTURE1);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.secondaryTexture);
		_gl.glUniform1i(_uTexture1, 1);
		_gl.glUniform2f(_uOffset, xOffset, yOffset);
		_gl.glUniform1f(_uScale, scale);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _entityBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 2 * Float.BYTES);
		_gl.glEnableVertexAttribArray(2);
		_gl.glVertexAttribPointer(2, 2, GL20.GL_FLOAT, false, 6 * Float.BYTES, 4 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// (we switch the atlas in and out since this will likely be a different sprite atlas, later).
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
		_gl.glUniform1i(_uTexture0, 0);
		_gl.glUniform1f(_uScale, 1.0f);
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
