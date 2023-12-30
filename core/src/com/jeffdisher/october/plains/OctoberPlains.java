package com.jeffdisher.october.plains;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;


public class OctoberPlains extends ApplicationAdapter
{
	public static final float TILE_EDGE_SIZE = 0.1f;

	// We need to render 2 kinds of things:  (1) Cuboid layers, (2) entities.
	// We will just use a single pair of shaders, at least for now, for both of these cases:
	// -vertex shader will move the rendering location by x/y uniform and pass through the texture u/v coordinates attribute
	// -fragment shader will sample the referenced texture coordinates and apply an alpha value
	// These shaders will be used for both rendering the layers and also the entities, just using different uniforms.
	private GL20 _gl;
	private int _program;
	private int _uOffset;
	private int _uTexture;
	private TextureAtlas _textureAtlas;
	private int _entityBuffer;
	private int _layerMeshBuffer;
	private int _layerTextureBuffer;

	private ClientLogic _client;

	@Override
	public void create ()
	{
		// Get the GLES20 context.
		_gl = Gdx.graphics.getGL20();
		
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
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	gl_FragColor = texture2D(uTexture, vTexture);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uTexture = _gl.glGetUniformLocation(_program, "uTexture");
		
		// Load the textures.
		try
		{
			// These are resolved by index so they must be loaded in the same order as the item registry.
			_textureAtlas = TextureAtlas.loadAtlas(_gl, new String[] {
					"air.png",
					"stone.png",
					"log.png",
					"plank.png",
					"unknown.jpeg",
			});
		}
		catch (IOException e)
		{
			// This is a fatal error.
			throw new AssertionError(e);
		}
		
		// Define the entity mesh and texture.
		_entityBuffer = _defineEntityBuffer(_gl);
		
		// Define the layer mesh.
		_layerMeshBuffer = _defineLayerMeshBuffer(_gl);
		
		// Define the starting layer texture coordinates.
		_layerTextureBuffer = _defineLayerTextureBuffer(_gl, _textureAtlas);
		
		// At this point, we can also create the basic OctoberProject client and testing environment.
		_client = new ClientLogic();
		_client.finishStartup();
	}

	@Override
	public void render ()
	{
		// Reset screen.
		_gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
		_gl.glClearColor(0.5f, 0.5f, 0.5f, 1.0f);
		_gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		_gl.glUseProgram(_program);
		
		// Handle inputs - we will only allow a single direction at a time.
		if (Gdx.input.isKeyPressed(Keys.DPAD_UP))
		{
			_client.stepNorth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_DOWN))
		{
			_client.stepSouth();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_RIGHT))
		{
			_client.stepEast();
		}
		else if (Gdx.input.isKeyPressed(Keys.DPAD_LEFT))
		{
			_client.stepWest();
		}
		
		// Draw the background layer.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textureAtlas.texture);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, -1.0f * _client.getXLocation(), -1.0f * _client.getYLocation());
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerMeshBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 0, 0);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _layerTextureBuffer);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 0, 0);
		
		int squaresPerCuboidEdge = 32;
		int verticesPerSquare = 6;
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, squaresPerCuboidEdge * squaresPerCuboidEdge * verticesPerSquare);
		
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
	}

	@Override
	public void dispose ()
	{
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

	private static int _defineEntityBuffer(GL20 gl)
	{
		float halfTile = TILE_EDGE_SIZE / 2.0f;
		float[] vertices = new float[] {
				-halfTile,  halfTile, 0.0f, 1.0f,
				-halfTile, -halfTile, 0.0f, 0.0f,
				 halfTile, -halfTile, 1.0f, 0.0f,
				
				 halfTile, -halfTile, 1.0f, 0.0f,
				 halfTile,  halfTile, 1.0f, 1.0f,
				-halfTile,  halfTile, 0.0f, 1.0f,
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
				float xCoord = (TILE_EDGE_SIZE * x) - (TILE_EDGE_SIZE * (float)tilesPerEdge / 2.0f);
				float yCoord = (TILE_EDGE_SIZE * y) - (TILE_EDGE_SIZE * (float)tilesPerEdge / 2.0f);
				
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

	private static int _defineLayerTextureBuffer(GL20 gl, TextureAtlas atlas)
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
				short blockValue = (short)(x % 5);
				
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
}
