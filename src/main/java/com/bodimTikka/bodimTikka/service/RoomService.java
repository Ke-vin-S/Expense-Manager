package com.bodimTikka.bodimTikka.service;

import com.bodimTikka.bodimTikka.dto.AddUserRequestDTO;
import com.bodimTikka.bodimTikka.dto.RoomDTO;
import com.bodimTikka.bodimTikka.dto.UserDTO;
import com.bodimTikka.bodimTikka.exceptions.InvalidRequestException;
import com.bodimTikka.bodimTikka.exceptions.NotFoundException;
import com.bodimTikka.bodimTikka.exceptions.UnauthorizedException;
import com.bodimTikka.bodimTikka.model.Room;
import com.bodimTikka.bodimTikka.model.User;
import com.bodimTikka.bodimTikka.model.UserInRoom;
import com.bodimTikka.bodimTikka.repository.RoomRepository;
import com.bodimTikka.bodimTikka.repository.UserInRoomRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RoomService {
    @Autowired
    private RoomRepository roomRepository;
    @Autowired
    private UserInRoomRepository userInRoomRepository;
    @Autowired
    private UserService userService;

    // TODO: handle null case?
    public List<UserDTO> getRoomUsers(UUID roomId) {
        List<UserInRoom> usersInRoom = userInRoomRepository.findUsersByRoomId(roomId);
        return usersInRoom.stream()
                .map(uir -> {
                    if (uir.isRegistered()) {
                        // Registered user: get details from User entity
                        User user = uir.getUser();
                        return new UserDTO(user.getId(), user.getName(), user.getEmail());
                    } else {
                        // Unregistered user: use name from UserInRoom, no email
                        return new UserDTO(uir.getId(), uir.getName(), null);
                    }
                })
                .collect(Collectors.toList());
    }

    public Boolean isUserInRoom(UUID userId, UUID roomId){
        return userInRoomRepository.existsByUserIdAndRoomIdAndIsStillAMember(userId, roomId, true);
    }

    public List<UUID> getUserInRoomIDs(UUID roomID){
        return userInRoomRepository.findUserIdsByRoomId(roomID);
    }

    // TODO: add main room members in get rooms dto and then can lazily load members for other rooms
    public Optional<Room> getRoomById(UUID id) {
        return roomRepository.findById(id);
    }

    public List<RoomDTO> getRoomsByUserId(UUID userId) {
        List<Room> rooms = roomRepository.findRoomsByUserId(userId);
        return rooms.stream()
                .map(room -> new RoomDTO(room.getId(), room.getName()))
                .collect(Collectors.toList());
    }

    public UserInRoom createUserInRoom(UUID roomId, AddUserRequestDTO request, String email){
        UUID senderId = getUserByEmailIfAdmin(roomId, email);
        String name = request.getName();
        boolean isRegistered = request.getIsRegistered();
        UUID userId = request.getUserId();

        verify(senderId, roomId, name, isRegistered, userId);

        return saveUserToRoom(roomId, userId, name, isRegistered);
    }

    private UserInRoom saveUserToRoom(UUID roomId, UUID userId, String name, boolean isRegistered) {
        UserInRoom userInRoom = new UserInRoom();
        userInRoom.setUser(userId != null ? new User(userId) : null);
        userInRoom.setRoom(new Room(roomId));
        userInRoom.setName(name);
        userInRoom.setStillAMember(true);
        userInRoom.setRegistered(isRegistered);
        userInRoom = userInRoomRepository.save(userInRoom);
        return userInRoom;
    }

    private void verify(UUID senderId, UUID roomId, String name, boolean isRegistered, UUID userId) {
        if (!userInRoomRepository.existsByUserIdAndRoomIdAndIsStillAMember(senderId, roomId, true)){
            throw new InvalidRequestException("Current user doesn't belong to the room");
        }

        if (name == null || name.trim().isEmpty()) {
            throw new InvalidRequestException("Name cannot be empty");
        }

        if (isRegistered) {
            if (userId == null) {
                throw new InvalidRequestException("User ID is required for registered users");
            }
            if (!userService.existsById(userId)) {
                throw new InvalidRequestException("User with ID " + userId + " does not exist");
            }
        } else {
            if (userId != null) {
                throw new InvalidRequestException("User ID must be null for unregistered users");
            }
        }

        if (isRegistered && userInRoomRepository.existsByUserIdAndRoomIdAndIsStillAMember(userId, roomId, true)) {
            throw new InvalidRequestException("User is already in the room");
        }
    }

    public Room createRoomForUser(Room room, String email) {
        UserDTO userProjection = userService.findUserProjectionByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found."));

        if (room.getName() == null || room.getName().trim().isEmpty()) {
            throw new InvalidRequestException("Room name cannot be null or empty.");
        }

        Room createdRoom = roomRepository.save(room);
        UUID userId = userProjection.getId();
        UUID roomId = createdRoom.getId();
        userInRoomRepository.addUserToRoom(userId, roomId, userProjection.getName());
        userInRoomRepository.addAdminUser(userId, roomId);

        return createdRoom;
    }

    public void deleteRoom(UUID roomId, String email) {
        getUserByEmailIfAdmin(roomId, email);

        roomRepository.deleteById(roomId);
    }

    private UUID getUserByEmailIfAdmin(UUID roomId, String email) {
        // FIXME: is this best error
        if (!roomRepository.existsById(roomId)){
            throw new InvalidRequestException("Invalid room ID");
        }
        UserDTO userProjection = userService.findUserProjectionByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found."));

        if (!userInRoomRepository.isUserAdmin(userProjection.getId(), roomId)){
            throw new UnauthorizedException("User is not an admin of this room.");
        }
        return userProjection.getId();
    }

    public boolean existsById(UUID roomId) {
        return roomRepository.existsById(roomId);
    }
}
