package com.jeffdisher.october.plains;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.data.IReadOnlyCuboidData;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BlockAddress;
import com.jeffdisher.october.types.CuboidAddress;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.PartialEntity;


/**
 * Controls the audio cues in the client.
 */
public class AudioManager
{
	public static final float AUDIO_RANGE_FLOAT = 10.0f;
	public static final float AUDIO_RANGE_SQUARED_FLOAT = AUDIO_RANGE_FLOAT * AUDIO_RANGE_FLOAT;
	public static final int AUDIO_RANGE_SQUARED = (int)AUDIO_RANGE_SQUARED_FLOAT;
	// We randomly make a noise every 1000 ticks - so every 10 seconds.
	public static int IDLE_SOUND_PER_TICK_DIVISOR = 1000;

	public static AudioManager load(Environment environment, Map<Cue, String> audioFileNames)
	{
		Sound walk = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.WALK)));
		Sound takeDamage = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.TAKE_DAMAGE)));
		Sound breakBlock = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.BREAK_BLOCK)));
		Sound placeBlock = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.PLACE_BLOCK)));
		Sound cowIdle = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.COW_IDLE)));
		Sound cowDeath = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.COW_DEATH)));
		Sound orcIdle = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.ORC_IDLE)));
		Sound orcDeath = Gdx.audio.newSound(Gdx.files.internal(audioFileNames.get(Cue.ORC_DEATH)));
		return new AudioManager(environment
				, walk
				, takeDamage
				, breakBlock
				, placeBlock
				, cowIdle
				, cowDeath
				, orcIdle
				, orcDeath
		);
	}

	private final Environment _environment;
	private final Random _random;
	private final Sound _walk;
	private final Sound _takeDamage;
	private final Sound _breakBlock;
	private final Sound _placeBlock;
	private final Sound _cowIdle;
	private final Sound _cowDeath;
	private final Sound _orcIdle;
	private final Sound _orcDeath;
	private final long _walkingId;

	private Entity _projectedEntity;
	private final Map<Integer, PartialEntity> _otherEntities;
	private final Map<CuboidAddress, IReadOnlyCuboidData> _cuboids;
	private boolean _isWalking;
	private byte _previousHealth;

	private AudioManager(Environment environment
			, Sound walk
			, Sound takeDamage
			, Sound breakBlock
			, Sound placeBlock
			, Sound cowIdle
			, Sound cowDeath
			, Sound orcIdle
			, Sound orcDeath
	)
	{
		_environment = environment;
		_random = new Random();
		_walk = walk;
		_takeDamage = takeDamage;
		_breakBlock = breakBlock;
		_placeBlock = placeBlock;
		_cowIdle = cowIdle;
		_cowDeath = cowDeath;
		_orcIdle = orcIdle;
		_orcDeath = orcDeath;
		
		_otherEntities = new HashMap<>();
		_cuboids = new HashMap<>();
		
		// We will start the walking sound as looping and pause it.
		_walkingId = _walk.loop();
		_walk.pause(_walkingId);
		
		// We start the previous health at 0 so we don't make the damage sound if we first load injured.
		_previousHealth = 0;
	}

	public void setThisEntity(Entity authoritativeEntity, Entity projectedEntity)
	{
		byte newHealth = authoritativeEntity.health();
		if (newHealth < _previousHealth)
		{
			_takeDamage.play();
		}
		_previousHealth = newHealth;
		
		_projectedEntity = projectedEntity;
	}

	public void setOtherEntity(PartialEntity otherEntity)
	{
		_otherEntities.put(otherEntity.id(), otherEntity);
	}

	public void removeOtherEntity(int entityId)
	{
		PartialEntity other  = _otherEntities.remove(entityId);
		
		// If they are close by and were removed, we will assume that they died (as opposed to being unloaded).
		AbsoluteLocation entityLocation = _projectedEntity.location().getBlockLocation();
		// We only care abut orcs and cows.
		Sound soundToPlay;
		switch(other.type())
		{
		case ORC:
			soundToPlay = _orcDeath;
			break;
		case COW:
			soundToPlay = _cowDeath;
			break;
			default:
				soundToPlay = null;
		}
		if (null != soundToPlay)
		{
			// Check if they are close enough to hear them.
			AbsoluteLocation otherLocation = other.location().getBlockLocation();
			int distanceSquared = _squaredDistance(entityLocation, otherLocation);
			if (distanceSquared <= AUDIO_RANGE_SQUARED)
			{
				// Play the sound.
				_playSound(soundToPlay, entityLocation, otherLocation, distanceSquared);
			}
		}
	}

	public void setCuboid(IReadOnlyCuboidData cuboid, Set<BlockAddress> changedBlocks)
	{
		// We want to compare this against the previous cuboid to play and break/place sounds if they are within range.
		CuboidAddress address = cuboid.getCuboidAddress();
		if (null != changedBlocks)
		{
			// This is a replacement cuboid so compare it to what we had.
			IReadOnlyCuboidData previous = _cuboids.get(address);
			AbsoluteLocation base = address.getBase();
			AbsoluteLocation entityLocation = _projectedEntity.location().getBlockLocation();
			for (BlockAddress relative : changedBlocks)
			{
				AbsoluteLocation absolute = base.getRelative(relative.x(), relative.y(), relative.z());
				int distanceSquared = _squaredDistance(entityLocation, absolute);
				if (distanceSquared <= AUDIO_RANGE_SQUARED)
				{
					// This block change is close to us so see if we care about it.
					BlockProxy oldProxy = new BlockProxy(relative, previous);
					BlockProxy newProxy = new BlockProxy(relative, cuboid);
					Block oldBlock = oldProxy.getBlock();
					Block newBlock = newProxy.getBlock();
					
					// We will check whether or not the block type can be replaced to determine if something was placed or broken.
					boolean canOldBeReplaced = _environment.blocks.canBeReplaced(oldBlock);
					boolean canNewBeReplaced = _environment.blocks.canBeReplaced(newBlock);
					if (canOldBeReplaced && !canNewBeReplaced)
					{
						// Placed a block.
						_playSound(_placeBlock, entityLocation, absolute, distanceSquared);
					}
					else if (!canOldBeReplaced && canNewBeReplaced)
					{
						// Break a block.
						_playSound(_breakBlock, entityLocation, absolute, distanceSquared);
					}
				}
			}
		}
		_cuboids.put(address, cuboid);
	}

	public void removeCuboid(CuboidAddress address)
	{
		_cuboids.remove(address);
	}

	public void tickCompleted()
	{
		// See if there is a nearby entity which should make a noise.
		AbsoluteLocation entityLocation = _projectedEntity.location().getBlockLocation();
		for (PartialEntity other : _otherEntities.values())
		{
			// We only care abut orcs and cows.
			Sound soundToPlay;
			switch(other.type())
			{
			case ORC:
				soundToPlay = _orcIdle;
				break;
			case COW:
				soundToPlay = _cowIdle;
				break;
				default:
					soundToPlay = null;
			}
			if (null != soundToPlay)
			{
				// Check if they are close enough to hear them.
				AbsoluteLocation otherLocation = other.location().getBlockLocation();
				int distanceSquared = _squaredDistance(entityLocation, otherLocation);
				if (distanceSquared <= AUDIO_RANGE_SQUARED)
				{
					// Generate a random number to see if this should make a sound.
					if (0 == _random.nextInt(IDLE_SOUND_PER_TICK_DIVISOR))
					{
						_playSound(soundToPlay, entityLocation, otherLocation, distanceSquared);
					}
				}
			}
		}
	}

	public void setWalking()
	{
		if (!_isWalking)
		{
			_walk.resume(_walkingId);
			_isWalking = true;
		}
	}

	public void setStanding()
	{
		if (_isWalking)
		{
			_walk.pause(_walkingId);
			_isWalking = false;
		}
	}


	private int _squaredDistance(AbsoluteLocation entityLocation, AbsoluteLocation blockLocation)
	{
		int xDistance = entityLocation.x() - blockLocation.x();
		int yDistance = entityLocation.y() - blockLocation.y();
		int zDistance = entityLocation.z() - blockLocation.z();
		return (xDistance * xDistance)
				+ (yDistance * yDistance)
				+ (zDistance * zDistance)
		;
	}

	private void _playSound(Sound soundToPlay, AbsoluteLocation entityLocation, AbsoluteLocation otherLocation, int distanceSquared)
	{
		float closeness = AUDIO_RANGE_SQUARED_FLOAT - (float)distanceSquared;
		float volume = closeness / (AUDIO_RANGE_SQUARED_FLOAT);
		float pan = (float)(otherLocation.x() - entityLocation.x()) / AUDIO_RANGE_FLOAT;
		soundToPlay.play(volume, 1.0f, pan);
	}


	public static enum Cue
	{
		WALK,
		TAKE_DAMAGE,
		BREAK_BLOCK,
		PLACE_BLOCK,
		COW_IDLE,
		COW_DEATH,
		ORC_IDLE,
		ORC_DEATH,
	};
}
