package com.jeffdisher.october.plains;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.graphics.GL20;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final int TEXT_TEXTURE_WIDTH_PIXELS = 256;
	public static final int TEXT_TEXTURE_HEIGHT_PIXELS = 128;

	private final GL20 _gl;

	private int _program;
	private int _uOffset;
	private int _uTexture;

	private int _smallTextTexture;
	private int _counterBuffer;

	public WindowManager(GL20 gl)
	{
		_gl = gl;
		
		// Create the program we will use for the window overlays.
		_program = RenderSupport.fullyLinkedProgram(_gl
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
						+ "	vec4 tex = texture2D(uTexture, vTexture);\n"
						+ "	vec4 biased = vec4(tex.r, tex.g, tex.b, tex.a);\n"
						+ "	gl_FragColor = vec4(biased.r, biased.g, biased.b, biased.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uTexture = _gl.glGetUniformLocation(_program, "uTexture");
		
		// Create the placeholder for our text texture.
		_smallTextTexture = gl.glGenTexture();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _smallTextTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, null);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// Create the relevant meshes.
		_counterBuffer = _defineTextVertexBuffer(_gl);
	}

	public void drawWindows(String text)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// Currently, we just have the text so draw that in the bottom.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_renderTextToImage(_gl, _smallTextTexture, text);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, 0.0f, -0.8f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _counterBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
	}


	private static void _renderTextToImage(GL20 gl, int texture, String text)
	{
		// We just use "something" for the font and size, for now - this will be made into something more specific, later.
		Font font = new Font("Arial", Font.BOLD, 96);
		BufferedImage image = new BufferedImage(TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = image.getGraphics();
		graphics.setFont(font);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, 0, TEXT_TEXTURE_HEIGHT_PIXELS);
		
		int channelsPerPixel = 2;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(TEXT_TEXTURE_WIDTH_PIXELS * TEXT_TEXTURE_HEIGHT_PIXELS * channelsPerPixel);
		textureBufferData.order(ByteOrder.nativeOrder());
		for (int y = 0; y < TEXT_TEXTURE_HEIGHT_PIXELS; ++y)
		{
			for (int x = 0; x < TEXT_TEXTURE_WIDTH_PIXELS; ++x)
			{
				int pixel = image.getRGB(x, y);
				// This data is pulled out as ARGB but we need to upload it as LA.
				// We draw white so just get any channel and the alpha.
				byte a = (byte)((0xFF000000 & pixel) >> 24);
				byte b = (byte) (0x000000FF & pixel);
				textureBufferData.put(new byte[] { b, a });
			}
		}
		((java.nio.Buffer) textureBufferData).flip();
		
		gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
		gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
	}

	private static int _defineTextVertexBuffer(GL20 gl)
	{
		float width = 0.4f;
		float height = 0.2f;
		float left = -width / 2.0f;
		float right = width / 2.0f;
		float top = height / 2.0f;
		float bottom = -height / 2.0f;
		float textureBaseU = 0.0f;
		float textureBaseV = 0.0f;
		float textureSize = 1.0f;
		float[] vertices = new float[] {
				left, bottom, textureBaseU, textureBaseV + textureSize,
				left, top, textureBaseU, textureBaseV,
				right, top, textureBaseU + textureSize, textureBaseV,
				
				right, top, textureBaseU + textureSize, textureBaseV,
				right, bottom, textureBaseU + textureSize, textureBaseV + textureSize,
				left, bottom, textureBaseU, textureBaseV + textureSize,
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
}
