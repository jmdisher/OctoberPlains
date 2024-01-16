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
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec4 tex = texture2D(uTexture, vTexture);\n"
						+ "	gl_FragColor = vec4(uLayerBrightness * tex.r, uLayerBrightness * tex.g, uLayerBrightness * tex.b, tex.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uTexture = _gl.glGetUniformLocation(_program, "uTexture");
		_uLayerBrightness = _gl.glGetUniformLocation(_program, "uLayerBrightness");
		
		// Define the entity mesh and texture.
		_entityBuffer = _defineEntityBuffer(_gl, _textureAtlas);
		
		// Define the layer mesh.
		_layerMeshBuffer = _defineLayerMeshBuffer(_gl);
		
		// Create the one-off text overlay vertex buffer.
		_textVertexBuffer = _defineTextVertexBuffer(_gl);
		
		_layerTextureMeshes = new HashMap<>();
	}

	public void renderScene(String text, float xTextOffset, float yTextOffset)
	{
		// Reset screen.
		_gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		_gl.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		_gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		_gl.glUseProgram(_program);
		
		// Draw the background layer.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
		_gl.glUniform1i(_uTexture, 0);
		
		// We render this relative to the entity, so figure out where it is.
		EntityLocation entityLocation = _thisEntity.location();
		AbsoluteLocation entityBlockLocation = entityLocation.getBlockLocation();
		float x = entityLocation.x();
		float y = entityLocation.y();
		
		// We want to render 9 tiles with 3 layers:  3x3x3, centred around the entity location.
		// (technically 4 tiles with 3 layers would be enough but that would require some extra logic)
		int cuboidSize = 32;
		float layerBrightness = 0.50f;
		for (int zOffset = -1; zOffset <= 1; ++zOffset)
		{
			_gl.glUniform1f(_uLayerBrightness, layerBrightness);
			layerBrightness += 0.25f;
			for (int xOffset = -cuboidSize; xOffset <= cuboidSize; xOffset += cuboidSize)
			{
				for (int yOffset = -cuboidSize; yOffset <= cuboidSize; yOffset += cuboidSize)
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
						float xCamera = TILE_EDGE_SIZE * ((float)(address.x() * cuboidSize) - x);
						float yCamera = TILE_EDGE_SIZE * ((float)(address.y() * cuboidSize) - y);
						_gl.glUniform2f(_uOffset, xCamera, yCamera);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerMeshBuffer);
						_gl.glEnableVertexAttribArray(0);
						_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
						_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, buffer);
						_gl.glEnableVertexAttribArray(1);
						_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
						
						int squaresPerCuboidEdge = 32;
						int verticesPerSquare = 6;
						_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, squaresPerCuboidEdge * squaresPerCuboidEdge * verticesPerSquare);
					}
				}
			}
		}
		
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
		int tilesPerEdge = 32;
		// The common mesh has 2 floats per vertex:  x and y (z is constant 0.0 for everything).
		int commonLayerSizeBytes = 1
				// tiles per layer
				* (tilesPerEdge * tilesPerEdge)
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
		for (int y = 0; y < tilesPerEdge; ++y)
		{
			for (int x = 0; x < tilesPerEdge; ++x)
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
		int tilesPerEdge = 32;
		// Build the single layer pointing at texture 0 - these are just texture coordinates.
		int singleLayerSizeBytes = 1
				// tiles per layer
				* (tilesPerEdge * tilesPerEdge)
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
		for (int y = 0; y < tilesPerEdge; ++y)
		{
			for (int x = 0; x < tilesPerEdge; ++x)
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
}
