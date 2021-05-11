package com.example.myproject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    static private int SELECT_PICTURE = 200;

    private Uri uri;
    private ImageView photo;
    private TextView location;
    private Button detect;
    private FirebaseAuth mAuth;
    private FirebaseFunctions mFunctions;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        photo = findViewById(R.id.imageView);
        location = findViewById(R.id.textView);
        detect = findViewById(R.id.detect);
        mFunctions = FirebaseFunctions.getInstance();

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();
        mAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            // Sign in success, update UI with the signed-in user's information
                            Log.d("TAG", "signInAnonymously:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            detect.setEnabled(true);
                        } else {
                            // If sign in fails, display a message to the user.
                            Log.w("TAG", "signInAnonymously:failure", task.getException());
                            Toast.makeText(MainActivity.this, "Authentication failed.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    public void select(View view) {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Pmage"), SELECT_PICTURE);
    }

    public void detect(View view) {
        if (uri == null)
            return;
        Bitmap bitmap = null;
        try {
            bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Scale down bitmap size
        bitmap = scaleBitmapDown(bitmap, 640);
        FirebaseFunctions firebaseFunctions = FirebaseFunctions.getInstance();

        // Convert bitmap to base64 encoded string
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
        byte[] imageBytes = byteArrayOutputStream.toByteArray();
        String base64encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
        firebaseFunctions = FirebaseFunctions.getInstance();

        // Create json request to cloud vision
        JsonObject request = new JsonObject();
// Add image to request
        JsonObject image = new JsonObject();
        image.add("content", new JsonPrimitive(base64encoded));
        request.add("image", image);
//Add features to the request
        JsonObject feature = new JsonObject();
        feature.add("maxResults", new JsonPrimitive(5));
        feature.add("type", new JsonPrimitive("LANDMARK_DETECTION"));
        JsonArray features = new JsonArray();
        features.add(feature);
        request.add("features", features);

        //Detect image
        annotateImage(request.toString())
                .addOnCompleteListener(new OnCompleteListener<JsonElement>() {
                    @Override
                    public void onComplete(@NonNull Task<JsonElement> task) {
                        if (!task.isSuccessful()) {
                            // Task failed with an exception
                            // ...
                            Toast.makeText((Context)MainActivity.this, (CharSequence)"Failed", Toast.LENGTH_SHORT).show();
                        } else {
                            for (JsonElement label : task.getResult().getAsJsonArray().get(0).getAsJsonObject().get("landmarkAnnotations").getAsJsonArray()) {
                                JsonObject labelObj = label.getAsJsonObject();
                                String landmarkName = labelObj.get("description").getAsString();
                                location.setText(landmarkName);
                                String entityId = labelObj.get("mid").getAsString();
                                float score = labelObj.get("score").getAsFloat();
                                JsonObject bounds = labelObj.get("boundingPoly").getAsJsonObject();
                                // Multiple locations are possible, e.g., the location of the depicted
                                // landmark and the location the picture was taken.
                                for (JsonElement loc : labelObj.get("locations").getAsJsonArray()) {
                                    JsonObject latLng = loc.getAsJsonObject().get("latLng").getAsJsonObject();
                                    double latitude = latLng.get("latitude").getAsDouble();
                                    double longitude = latLng.get("longitude").getAsDouble();
                                }
                            }

                        }
                    }
                });
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == SELECT_PICTURE) {
                if (data != null) {
                    uri = data.getData();
                    photo.setImageURI(uri);
                }
            }
        }
    }

    private Bitmap scaleBitmapDown(Bitmap bitmap, int maxDimension) {
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        int resizedWidth = maxDimension;
        int resizedHeight = maxDimension;
        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = (int)((float)maxDimension * (float)originalWidth / (float)originalHeight);
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension;
            resizedHeight = (int)((float)maxDimension * (float)originalHeight / (float)originalWidth);
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension;
            resizedWidth = maxDimension;
        }

        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false);
    }


    private Task<JsonElement> annotateImage(String requestJson) {
        return mFunctions
                .getHttpsCallable("annotateImage")
                .call(requestJson)
                .continueWith(new Continuation<HttpsCallableResult, JsonElement>() {
                    @Override
                    public JsonElement then(@NonNull Task<HttpsCallableResult> task) {
                        // This continuation runs on either success or failure, but if the task
                        // has failed then getResult() will throw an Exception which will be
                        // propagated down.
                        return JsonParser.parseString(new Gson().toJson(task.getResult().getData()));
                    }
                });
    }

}