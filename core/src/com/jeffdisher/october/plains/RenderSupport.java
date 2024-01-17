package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.registries.AspectRegistry;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.utils.Assert;


public class RenderSupport
{
	// The screen is 2.0/2.0 (-1.0 - 1.0) and we want roughly 40x40 tiles on screen, so use this tile edge size.
	public static final float TILE_EDGE_SIZE = 0.05f;
	public static final int CUBOID_EDGE_TILE_COUNT = 32;
	public static final int VERTICES_PER_SQUARE = 6;

	// We need to render 2 kinds of things:  (1) Cuboid layers, (2) entities.
	// We will just use a single pair of shaders, at least for now, for both of these cases:
	// -vertex shader will move the rendering location by x/y uniform and pass through the texture u/v coordinates attribute
	// -fragment shader will sample the referenced texture coordinates and apply an alpha value
	// These shaders will be used for both rendering the layers and also the entities, just using different uniforms.
	private final GL20 _gl;
	private final TextureAtlas _textureAtlas;
	
	private int _program;
	private int _uOffset;
	private int _uTexture;
	private int _uLayerBrightness;
	private int _uLayerAlpha;
	private int _uColourBias;
	private int _entityBuffer;
	private int _layerMeshBuffer;
	private int _textVertexBuffer;

	private Entity _thisEntity;
	private final Map<CuboidAddress, int[]> _layerTextureMeshes;

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
						+ "attribute vec2 aTexture;\n"
						+ "uniform vec2 uOffset;\n"
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vTexture = aTexture;\n"
						+ "	gl_Position = vec4(aPosition.x + uOffset.x, aPosition.y + uOffset.y, 0.0, 1.0);\n"
						+ "}\n"
				, "#version 100\n"
						+ "precision mediump float;\n"
						+ "uniform sampler2D uTexture;\n"
						+ "uniform float uLayerBrightness;\n"
						+ "uniform float uLayerAlpha;\n"
						+ "uniform vec4 uColourBias;\n"
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec4 tex = texture2D(uTexture, vTexture);\n"
						+ "	vec4 biased = vec4(clamp(uColourBias.r + tex.r, 0.0, 1.0), clamp(uColourBias.g + tex.g, 0.0, 1.0), clamp(uColourBias.b + tex.b, 0.0, 1.0), clamp(uColourBias.a + tex.a, 0.0, 1.0));\n"
						+ "	gl_FragColor = vec4(uLayerBrightness * biased.r, uLayerBrightness * biased.g, uLayerBrightness * biased.b, uLayerAlpha * biased.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uTexture = _gl.glGetUniformLocation(_program, "uTexture");
		_uLayerBrightness = _gl.glGetUniformLocation(_program, "uLayerBrightness");
		_uLayerAlpha = _gl.glGetUniformLocation(_program, "uLayerAlpha");
		_uColourBias = _gl.glGetUniformLocation(_program, "uColourBias");
		
		// Define the entity mesh and texture.
		_entityBuffer = _defineEntityBuffer(_gl, _textureAtlas);
		
		// Define the layer mesh.
		_layerMeshBuffer = _defineLayerMeshBuffer(_gl);
		
		// Create the one-off text overlay vertex buffer.
		_textVertexBuffer = _defineTextVertexBuffer(_gl);
		
		_layerTextureMeshes = new HashMap<>();
	}

	/**
	 * Renders a single frame of the scene, optionally including a floating text element.
	 * 
	 * @param text If non-null, will render this text in the floating text texture at the given coordinates.
	 * @param xTextOffset The X-offset of the text box, in GL coordinates.
	 * @param yTextOffset The Y-offset of the text box, in GL coordinates.
	 * @param xMouse The X-location of the mouse, in GL coordinates.
	 * @param yMouse The Y-location of the mouse, in GL coordinates.
	 * @param zLayerOffset The Z-layer where the mouse "is", in terms of -1, 0, or +1 from the entity location.
	 */
	public void renderScene(String text, float xTextOffset, float yTextOffset, float xMouse, float yMouse, int zLayerOffset)
	{
		// We render this relative to the entity, so figure out where it is.
		EntityLocation entityLocation = _thisEntity.location();
		AbsoluteLocation entityBlockLocation = entityLocation.getBlockLocation();
		float x = entityLocation.x();
		float y = entityLocation.y();
		
		// Determine which tile is selected under the mouse.
		CuboidAddress selectedCuboid;
		BlockAddress selectedBlock;
		{
			// TILE_EDGE_SIZE is set to 0.05 so we have 40 tiles along the edge of the screen.
			AbsoluteLocation selectedLocation = _entityOffset(xMouse, yMouse, zLayerOffset, entityBlockLocation);
			selectedCuboid = selectedLocation.getCuboidAddress();
			
			int[] meshLayers = _layerTextureMeshes.get(selectedCuboid);
			// This may not be here if the server hasn't sent it yet.
			selectedBlock = (null != meshLayers)
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
		_gl.glUniform1i(_uTexture, 0);
		
		// Set any starting uniform values.
		_gl.glUniform4f(_uColourBias, 0.0f, 0.0f, 0.0f, 0.0f);
		
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
					
					int[] meshLayers = _layerTextureMeshes.get(address);
					// This may not be here if the server hasn't sent it yet.
					if (null != meshLayers)
					{
						byte zLayer = offsetLocation.getBlockAddress().z();
						int buffer = meshLayers[zLayer];
						
						// Be sure to position the camera above the entity, so calculate the offset where we will draw this layer.
						float xCamera = TILE_EDGE_SIZE * ((float)(address.x() * CUBOID_EDGE_TILE_COUNT) - x);
						float yCamera = TILE_EDGE_SIZE * ((float)(address.y() * CUBOID_EDGE_TILE_COUNT) - y);
						_gl.glUniform2f(_uOffset, xCamera, yCamera);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerMeshBuffer);
						_gl.glEnableVertexAttribArray(0);
						_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, buffer);
						_gl.glEnableVertexAttribArray(1);
						_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
						
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
				_gl.glActiveTexture(GL20.GL_TEXTURE0);
				_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
				_gl.glUniform1i(_uTexture, 0);
				_gl.glUniform2f(_uOffset, 0.0f, 0.0f);
				_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _entityBuffer);
				_gl.glEnableVertexAttribArray(0);
				_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
				_gl.glEnableVertexAttribArray(1);
				_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
				_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
				
				// (we switch the atlas in and out since this will likely be a different sprite atlas, later).
				_gl.glActiveTexture(GL20.GL_TEXTURE0);
				_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
				_gl.glUniform1i(_uTexture, 0);
			}
		}
		
		
		if (null != text)
		{
			// For now, we will just draw the text on top of the entity (just a test).
			_gl.glActiveTexture(GL20.GL_TEXTURE0);
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.smallTextTexture);
			TextureAtlas.renderTextToImage(_gl, _textureAtlas.smallTextTexture, text);
			_gl.glUniform1i(_uTexture, 0);
			_gl.glUniform2f(_uOffset, xTextOffset, yTextOffset);
			_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _textVertexBuffer);
			_gl.glEnableVertexAttribArray(0);
			_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
			_gl.glEnableVertexAttribArray(1);
			_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
			_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		}
	}

	public void setThisEntity(Entity thisEntity)
	{
		_thisEntity = thisEntity;
	}

	public void setOneCuboid(IReadOnlyCuboidData cuboid)
	{
		// Generate all 32 layers.
		// See if they already exist.
		int[] layers = _layerTextureMeshes.get(cuboid.getCuboidAddress());
		if (null == layers)
		{
			layers = new int[32];
			for (int i = 0; i < layers.length; ++i)
			{
				layers[i] = _gl.glGenBuffer();
			}
			_layerTextureMeshes.put(cuboid.getCuboidAddress(), layers);
		}
		for (byte zLayer = 0; zLayer < 32; ++zLayer)
		{
			layers[zLayer] = _defineLayerTextureBuffer(_gl, _textureAtlas, cuboid, zLayer);
		}
	}

	public void removeCuboid(CuboidAddress address)
	{
		int[] layers = _layerTextureMeshes.remove(address);
		Assert.assertTrue(null != layers);
		for (int layer : layers)
		{
			_gl.glDeleteBuffer(layer);
		}
	}

	public AbsoluteLocation entityOffset(float xMouse, float yMouse, int zLayerOffset)
	{
		return _entityOffset(xMouse, yMouse, zLayerOffset, _thisEntity.location().getBlockLocation());
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
		float textureSize = atlas.coordinateSize;
		// (we use the unknown texture at index 4, for now).
		float[] uv = atlas.baseOfTexture(4);
		float textureBaseU = uv[0];
		float textureBaseV = uv[1];
		float[] vertices = new float[] {
				0.0f, TILE_EDGE_SIZE, textureBaseU, textureBaseV + textureSize,
				0.0f, 0.0f, textureBaseU, textureBaseV,
				TILE_EDGE_SIZE, 0.0f, textureBaseU + textureSize, textureBaseV,
				
				TILE_EDGE_SIZE, 0.0f, textureBaseU + textureSize, textureBaseV,
				TILE_EDGE_SIZE, TILE_EDGE_SIZE, textureBaseU + textureSize, textureBaseV + textureSize,
				 0.0f, TILE_EDGE_SIZE, textureBaseU, textureBaseV + textureSize,
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
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
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
		// Build the single layer pointing at texture 0 - these are just texture coordinates.
		int singleLayerSizeBytes = 1
				// tiles per layer
				* (CUBOID_EDGE_TILE_COUNT * CUBOID_EDGE_TILE_COUNT)
				// triangles per tile
				* 2
				// vertices per triangle
				* 3
				// uv per vertex
				* (Float.BYTES * 2)
		;
		ByteBuffer singleLayerData = ByteBuffer.allocateDirect(singleLayerSizeBytes);
		singleLayerData.order(ByteOrder.nativeOrder());
		// Populate the common mesh.
		FloatBuffer textureBuffer = singleLayerData.asFloatBuffer();
		float textureSize = atlas.coordinateSize;
		for (int y = 0; y < CUBOID_EDGE_TILE_COUNT; ++y)
		{
			for (int x = 0; x < CUBOID_EDGE_TILE_COUNT; ++x)
			{
				short blockValue = cuboid.getData15(AspectRegistry.BLOCK, new BlockAddress((byte)x, (byte)y, zLayer));
				
				float[] uv = atlas.baseOfTexture(blockValue);
				float textureBaseU = uv[0];
				float textureBaseV = uv[1];
				
				// NOTE:  We invert the textures here (probably not ideal).
				float[] tl = new float[]{textureBaseU, textureBaseV};
				float[] tr = new float[]{textureBaseU + textureSize, textureBaseV};
				float[] br = new float[]{textureBaseU + textureSize, textureBaseV + textureSize};
				float[] bl = new float[]{textureBaseU, textureBaseV + textureSize};
				
				textureBuffer.put(bl);
				textureBuffer.put(br);
				textureBuffer.put(tr);
				
				textureBuffer.put(bl);
				textureBuffer.put(tr);
				textureBuffer.put(tl);
			}
		}
		((java.nio.Buffer) singleLayerData).position(0);
		
		int commonTextures = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, commonTextures);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, singleLayerSizeBytes, singleLayerData.asFloatBuffer(), GL20.GL_DYNAMIC_DRAW);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
		return commonTextures;
	}

	private static int _defineTextVertexBuffer(GL20 gl)
	{
		float textureSize = 1.0f;
		float textureBaseU = 0.0f;
		float textureBaseV = 0.0f;
		float[] vertices = new float[] {
				0.0f, TILE_EDGE_SIZE, textureBaseU, textureBaseV + textureSize,
				0.0f, 0.0f, textureBaseU, textureBaseV,
				TILE_EDGE_SIZE, 0.0f, textureBaseU + textureSize, textureBaseV,
				
				TILE_EDGE_SIZE, 0.0f, textureBaseU + textureSize, textureBaseV,
				TILE_EDGE_SIZE, TILE_EDGE_SIZE, textureBaseU + textureSize, textureBaseV + textureSize,
				 0.0f, TILE_EDGE_SIZE, textureBaseU, textureBaseV + textureSize,
		};
		ByteBuffer direct = ByteBuffer.allocateDirect(vertices.length * Float.BYTES);
		direct.order(ByteOrder.nativeOrder());
		for (float f : vertices)
		{
			direct.putFloat(f);
		}
		((java.nio.Buffer) direct).flip();
		
		int textBuffer = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, textBuffer);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, direct.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		return textBuffer;
	}

	private AbsoluteLocation _entityOffset(float xMouse, float yMouse, int zLayerOffset, AbsoluteLocation entityBlockLocation)
	{
		int xBlockOffset = (int)(xMouse / TILE_EDGE_SIZE);
		int yBlockOffset = (int)(yMouse / TILE_EDGE_SIZE);
		return entityBlockLocation.getRelative(xBlockOffset, yBlockOffset, zLayerOffset);
	}
}
