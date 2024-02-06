package com.jeffdisher.october.plains;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;


// Please note that on macOS your application needs to be started with the -XstartOnFirstThread JVM argument
public class DesktopLauncher
{
	public static void main (String[] args)
	{
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		config.setForegroundFPS(60);
		config.setTitle("OctoberPlains");
		// Increase our default size.
		config.setWindowedMode(1280, 960);
		new Lwjgl3Application(new OctoberPlains(args), config);
	}
}
